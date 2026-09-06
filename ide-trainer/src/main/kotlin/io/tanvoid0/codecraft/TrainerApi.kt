package io.tanvoid0.codecraft

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files

// ponytail: fixed port, loopback only, no auth - a personal dev-machine tool.
// Override with an env var if 47823 collides with something else on your box.
private val TRAINER_API_PORT = System.getenv("TRAINER_API_PORT")?.toIntOrNull() ?: 47823

/**
 * A tiny local HTTP API over the tool window's own live state, so an external
 * client - a shell, a script, or an AI assistant with a fetch/bash tool - can
 * read progress and edit lessons without hand-editing `experiment.json` or
 * racing the SQLite file the tool window already has open.
 *
 * Loopback-only, no auth: a personal dev-machine tool, not meant to be
 * reachable off 127.0.0.1. If the port is already taken (e.g. two projects
 * open), the tool window still works - it just has no API.
 */
class TrainerApi(
    private val project: Project,
    private val experiments: () -> List<Experiment>,
    private val current: () -> Pair<Experiment?, CurriculumStore?>,
    private val reload: () -> Unit,
) {
    private var server: HttpServer? = null

    fun start() {
        server = runCatching {
            HttpServer.create(InetSocketAddress("127.0.0.1", TRAINER_API_PORT), 0).apply {
                createContext("/") { ex -> if (ex.requestURI.path == "/") docs(ex) else respond(ex, 404, err("no such route - see /")) }
                createContext("/api/progress") { ex -> progress(ex) }
                createContext("/api/experiments") { ex -> experimentsRoute(ex) }
                executor = null
                start()
            }
        }.getOrNull()
    }

    fun stop() = server?.stop(0)

    // ---- routes ------------------------------------------------------------

    private fun experimentsRoute(ex: HttpExchange) {
        val segments = ex.requestURI.path.removePrefix("/api/experiments").trim('/').split("/").filter { it.isNotEmpty() }
        when {
            segments.isEmpty() && ex.requestMethod == "GET" -> listExperiments(ex)
            segments.isEmpty() && ex.requestMethod == "POST" -> createExperiment(ex)
            segments.size == 2 && segments[1] == "lessons" && ex.requestMethod == "GET" -> getLessons(ex, segments[0])
            segments.size == 2 && segments[1] == "lessons" && ex.requestMethod == "PUT" -> putLessons(ex, segments[0])
            else -> respond(ex, 404, err("no such route - see /"))
        }
    }

    private fun listExperiments(ex: HttpExchange) {
        val openName = onEdt { current().first?.name }
        val arr = JsonArray()
        onEdt { experiments() }.forEach { e ->
            arr.add(JsonObject().apply {
                addProperty("name", e.name)
                addProperty("lessonCount", e.lessons.size)
                addProperty("open", e.name == openName)
            })
        }
        respond(ex, 200, JsonObject().apply { add("experiments", arr) })
    }

    /** Progress for whichever experiment is currently open in the tool window. */
    private fun progress(ex: HttpExchange) {
        if (ex.requestMethod != "GET") return respond(ex, 405, err("GET only"))
        val result = onEdt {
            val (exp, store) = current()
            if (exp == null || store == null) return@onEdt err("No experiment open")
            JsonObject().apply {
                addProperty("experiment", exp.name)
                add("progress", Progress.build(store.steps, store.curriculum?.plan, store.ticksSnapshot(), Progress.now()))
                addProperty("ticksToday", store.ticksToday())
                addProperty("startedCurrentStepAt", store.currentStep()?.let { store.startedStepAt(it) })
            }
        }
        respond(ex, if (result.has("error")) 409 else 200, result)
    }

    private fun getLessons(ex: HttpExchange, name: String) {
        val exp = onEdt { experiments() }.firstOrNull { it.name == name }
            ?: return respond(ex, 404, err("no such experiment: $name"))
        respond(ex, 200, JsonObject().apply { add("lessons", Json.gson.toJsonTree(exp.lessons)) })
    }

    /**
     * Replaces the whole lesson set - the caller sends the full array back,
     * same shape `GET .../lessons` returned. Lessons live in the step file
     * they belong to, so this rewrites one file per step, and refuses a
     * lesson naming a step this experiment does not have.
     */
    private fun putLessons(ex: HttpExchange, name: String) {
        val exp = onEdt { experiments() }.firstOrNull { it.name == name }
            ?: return respond(ex, 404, err("no such experiment: $name"))
        val body = runCatching { Json.gson.fromJson(ex.requestBody.readBytes().toString(StandardCharsets.UTF_8), JsonObject::class.java) }.getOrNull()
            ?: return respond(ex, 400, err("invalid JSON body"))
        val lessonsJson = body.get("lessons") ?: return respond(ex, 400, err("body needs a \"lessons\" array"))
        val newLessons = runCatching { Json.gson.fromJson(lessonsJson, Array<Lesson>::class.java).toList() }.getOrNull()
            ?: return respond(ex, 400, err("lessons did not parse - see /"))

        val files = Content.stepFiles(exp.dir)
        val unknown = newLessons.map { it.step }.filter { !files.containsKey(it) }.distinct()
        if (unknown.isNotEmpty()) return respond(ex, 400, err("no such step(s): ${unknown.joinToString(",")}"))

        val write = runCatching {
            files.forEach { (id, file) ->
                val obj = Content.read(file) ?: return@forEach
                // The step file already says which step it is; the stored copy
                // drops the repeated `step` field rather than let the two drift.
                val mine = newLessons.filter { it.step == id }.map { it.copy(step = "") }
                obj.add("lessons", Json.gson.toJsonTree(mine))
                Files.writeString(file, Json.gson.toJson(obj))
            }
        }
        if (write.isFailure) return respond(ex, 500, err("write failed: ${write.exceptionOrNull()?.message}"))

        ApplicationManager.getApplication().invokeLater { reload() }
        respond(ex, 200, JsonObject().apply { addProperty("ok", true); addProperty("lessonCount", newLessons.size) })
    }

    /**
     * Registers a new course: writes `experiments/<name>/experiment.json` only.
     * The curriculum itself - `curriculum.json` and the step files - is
     * authored separately; an experiment with none simply fails to load.
     */
    private fun createExperiment(ex: HttpExchange) {
        val root = Experiments.root(project) ?: return respond(ex, 500, err("no project root"))
        val req = runCatching { Json.gson.fromJson(ex.requestBody.readBytes().toString(StandardCharsets.UTF_8), Experiment::class.java) }.getOrNull()
            ?: return respond(ex, 400, err("invalid JSON body"))
        if (req.name.isBlank()) return respond(ex, 400, err("\"name\" is required"))
        if (onEdt { experiments() }.any { it.name == req.name }) return respond(ex, 409, err("experiment \"${req.name}\" already exists"))

        val write = runCatching {
            val dir = root.resolve(req.name)
            Files.createDirectories(dir)
            Files.writeString(dir.resolve("experiment.json"), Json.gson.toJson(req))
        }
        if (write.isFailure) return respond(ex, 500, err("write failed: ${write.exceptionOrNull()?.message}"))

        ApplicationManager.getApplication().invokeLater { reload() }
        respond(ex, 200, JsonObject().apply { addProperty("ok", true); addProperty("name", req.name) })
    }

    private fun docs(ex: HttpExchange) {
        val html = """
            <html><head><title>Trainer API</title></head><body style="font-family:sans-serif;max-width:640px;margin:2rem auto;line-height:1.5">
            <h1>Trainer API</h1>
            <p>Loopback-only, no auth. Reads/writes the curriculum in the project this IDE has open.</p>
            <table border="1" cellpadding="6" style="border-collapse:collapse">
            <tr><th>Route</th><th>What</th></tr>
            <tr><td>GET /api/experiments</td><td>List courses, which one is open</td></tr>
            <tr><td>GET /api/progress</td><td>Steps/tasks done, current step, today's tick count - for the OPEN course</td></tr>
            <tr><td>GET /api/experiments/{name}/lessons</td><td>That course's IDE-lesson definitions</td></tr>
            <tr><td>PUT /api/experiments/{name}/lessons</td><td>Replace them - body <code>{"lessons":[...]}</code>, same shape the GET returns</td></tr>
            <tr><td>POST /api/experiments</td><td>Register a new course manifest - body <code>{"name","cheatsheet"?}</code>. Does NOT create curriculum.json or the step files.</td></tr>
            </table>
            <p>Example:</p>
            <pre>curl http://127.0.0.1:$TRAINER_API_PORT/api/progress</pre>
            </body></html>
        """.trimIndent()
        val bytes = html.toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    // ---- plumbing ------------------------------------------------------------

    private fun err(msg: String) = JsonObject().apply { addProperty("error", msg) }

    private fun respond(ex: HttpExchange, code: Int, body: JsonObject) {
        val bytes = Json.gson.toJson(body).toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    /** IntelliJ model state (the tool window's `experiments`/`store`) is EDT-owned; the HTTP thread only ever borrows it briefly. */
    private fun <T> onEdt(body: () -> T): T {
        var result: T? = null
        ApplicationManager.getApplication().invokeAndWait { result = body() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}
