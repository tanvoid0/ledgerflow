package io.tanvoid0.codecraft

import java.nio.file.Files
import java.nio.file.Path

/**
 * A throwaway experiment folder in the layout `Content` expects: a spine, one
 * file per step, and nothing else. Tests that tick get one of these rather
 * than the real curriculum, which they would otherwise write to.
 */
fun writeExperiment(dir: Path, vararg steps: String): Path {
    Files.createDirectories(dir.resolve(Content.STEPS))
    val names = steps.mapIndexed { i, body ->
        val id = Json.parse(body)?.get("id")?.asString ?: "0${i + 1}"
        "$id.json".also { Files.writeString(dir.resolve(Content.STEPS).resolve(it), body) }
    }
    Files.writeString(
        dir.resolve(Content.CURRICULUM),
        """
        { "project": "Fixture", "stages": [
            { "letter": "A", "name": "Stage", "steps": [${names.joinToString(",") { "\"$it\"" }}] } ] }
        """.trimIndent()
    )
    return dir
}

/** `progress.json` holding exactly these ticks, as the plugin would have written it. */
fun writeProgress(dir: Path, ticks: Set<String>, updatedAt: String) {
    val byStep = ticks.groupBy({ it.substringBefore(':') }, { it.substringAfter(':') })
    val steps = byStep.entries.joinToString(",") { (id, keys) ->
        """{ "id": "$id", "tasks": [${keys.joinToString(",") { "\"$it\"" }}] }"""
    }
    Files.writeString(
        dir.resolve(Content.PROGRESS),
        """{ "progress": { "updatedAt": "$updatedAt", "steps": [$steps] } }"""
    )
}
