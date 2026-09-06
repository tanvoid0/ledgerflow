# LedgerFlow → IntelliJ: plan and status

Goal: the curriculum board runs **inside** IntelliJ and teaches the IDE while
you build, the way JetBrains' own IDE Features Trainer does — teach a feature,
watch you actually use it, tick it, move on.

Shape, decided: the plugin is a **generic module in this repo**
(`ide-trainer/`), not a LedgerFlow plugin. It drives *experiments*; LedgerFlow
is experiment #1. Nothing in the plugin mentions the curriculum, and
`ledgerflow.html` / `ledgerflow.json` stay where they are, still working in a
plain browser.

**Status: built, tested, and now native.** The first version painted
`ledgerflow.html` in a JCEF browser inside the dock. A web page is the wrong
thing to put in a 400px sidebar — wrong fonts, wrong colours in dark theme, a
loopback server to dodge CORS, and a JCEF dependency that had already moved
once between releases — so the UI is Swing now, on the platform's own
components. 51 tests pass, including one that builds every step of the real
curriculum headlessly (JCEF made that impossible) and one that proves the
plugin writes a progress block identical to the browser board's. The design is in
[`ide-trainer/NATIVE-UI-PLAN.md`](ide-trainer/NATIVE-UI-PLAN.md); build and
install instructions in [`ide-trainer/README.md`](ide-trainer/README.md).

---

## 1. The model: teach → do → detect → advance

1. **Teach** — the lesson names an IDE feature in context: "you're about to
   write your first entity; open it with *Navigate → Class* (`Ctrl+N`)".
2. **Do** — the learner performs it in the *real* IDE on the *real* project.
3. **Detect** — the plugin listens for the action actually happening. No "I did
   it" checkbox on faith.
4. **Advance** — the lesson ticks; the curriculum task carries on as normal.

Lessons attach to curriculum **steps**, so a feature is taught in the step where
its absence first hurts — the same rule the curriculum already follows for
concepts.

We do **not** depend on the IDE Features Trainer plugin. Its lesson API is
internal and undocumented for third parties. We copy the pattern on stable
platform listeners.

---

## 2. Layout as built

```
ledgerflow/
├── ledgerflow.html            board — still works in a browser, off the same file
├── ledgerflow.json            curriculum + progress — untouched schema
├── account-service/ …         the curriculum's actual output
└── ide-trainer/               the plugin module
    ├── build.gradle.kts       IntelliJ Platform Gradle Plugin 2.18.1, Kotlin 2.4.0
    ├── gradle.properties      idePath → the locally installed IDE
    ├── src/main/kotlin/io/ledgerflow/trainer/
    │   ├── Curriculum.kt                 the data model, and labelPath()
    │   ├── CurriculumStore.kt            load / tick / write the board's file
    │   ├── Ide.kt                        openFile / runInTerminal / copy
    │   ├── LessonEngine.kt               listeners, arming, lesson progress
    │   ├── Experiment.kt                 experiment manifests, current-step read
    │   └── ui/                           tool window, step view, tree, setup, blocks
    ├── src/test/kotlin/…                 51 tests, incl. real platform events
    ├── tools/audit_triggers.py           trigger ids vs an installed IDE
    ├── src/main/resources/META-INF/plugin.xml
    └── experiments/
        └── ledgerflow/experiment.json    20 lessons across the 17 steps
```

The tool window has three tabs — Step, Steps, Setup — with the IDE lessons for
the current step under the step itself.

---

## 3. Two decisions that shrank the work

### The board is not lesson-aware

The first draft had lesson cards rendered *in the page*, attached to task rows.
That would have coupled every board to the plugin and meant patching the
renderer. Instead **the plugin renders lessons in Swing**, below the board, and
reads which step you are on out of the board's own data file
(`progress.summary.currentStep` — the board already computes it; deriving it
twice is how the two copies disagree).

Result: any HTML board works unmodified, and LedgerFlow's board keeps working
in Chrome with no plugin at all.

### The board was served over loopback, and then wasn't

Loading the page over `file://` broke its `fetch("ledgerflow.json")` on CORS,
and the IDE's built-in web server no longer serves project files in this build
(appendix A, measured). So the plugin served the two files itself: `BoardServer`,
40 lines of `com.sun.net.httpserver`, GET-only on a loopback port.

That is all gone. The board is not loaded in the IDE at all now — the tool
window renders the curriculum straight out of `ledgerflow.json` in Swing, so
there is no page to serve, no CORS, no JCEF, and no bridge protocol. What
replaced it is the one hard constraint of the native UI: both writers share
that file, so the plugin reproduces the board's `buildProgress()` exactly, and
a test rebuilds the real file's progress block from its own ticks to prove it.

`ledgerflow.html` is unmodified and still opens in a browser off the same file.
Its `window.__lfBridge` hooks are simply never defined in-IDE any more.

---

## 4. Lesson engine

`LessonEngine.kt`, all ordinary message-bus subscriptions:

| trigger | listener |
|---|---|
| `action` | `AnActionListener.TOPIC`, matched on `ActionManager.getId` |
| `run` | `ExecutionManager.EXECUTION_TOPIC`, matched on executor id |
| `fileOpen` | `FileEditorManagerListener`, suffix or `*.ext` |
| `fileCreate` | `BulkFileListener`, `VFileCreateEvent` — proof for "create X" tasks |
| `toolWindow` | `ToolWindowManagerListener.toolWindowShown` |
| `breakpoint` | `XBreakpointListener`, breakpoint type id or `*` |

Only the current step's lessons are armed. Detected lessons are written to
`experiments/ledgerflow/progress.json`, deliberately separate from
`ledgerflow.json` so the board's schema needs no migration.

Two rules borrowed from the Trainer, both honoured:

- **Detect the effect when the gesture is ambiguous.** "Open the HTTP client"
  has many entry points, so that lesson watches for a `.http` file being
  opened, not one action id.
- **Everything is skippable.** Ultimate-only features (Database, Endpoints,
  Profiler, HTTP client) carry a Community-edition `fallbackTip`, and a
  detector that cannot fire can never wedge the curriculum. Skipped is recorded
  as skipped, not done.

---

## 5. What changed in `ledgerflow.html`

Nothing, now. Four hooks were added for the JCEF version, all guarded on
`window.__lfBridge`; the native tool window never defines it, so they are inert
in the IDE and unchanged in a browser:

- `ideOpen` — bridge `openFile` when in-IDE, the old `jetbrains://` URI
  otherwise (which is dead, appendix A, but costs nothing to leave).
- `ideRun` + a `run` button on shell blocks, hidden by CSS unless `body.in-ide`.
- `save()` — bridge `writeDoc` when in-IDE. JCEF has no File System Access
  picker, so without this the board would be read-only in the tool window.
- `syncConn()` — says "writing through IntelliJ" instead of "read-only".

`labelPath()` now exists twice, once per language, both checked against the
same nine cases — the price of the native panel offering the same "open this
file" button the page does. Path resolution in the IDE is `FilenameIndex` by
name plus a path-suffix filter, which handles the 26 module-relative labels and
survives the mid-curriculum repo reorganisation with no prefix table.

---

## 6. Lesson catalog

Twenty lessons in `experiments/ledgerflow/experiment.json`, one to three per
step, chosen by "teach it where its absence first hurts":

| step | teaches |
|---|---|
| 01 | Search Everywhere · Navigate → Class · run from the gutter |
| 02 | Database tool window |
| 03 | Services tool window · HTTP client `.http` files |
| 04 | Maven tool window and reactor reload |
| 05 | split editor |
| 06 | breakpoints and conditional breakpoints over print debugging |
| 07 | Find Usages (`Alt+F7`) |
| 08 | diff viewer for schema compatibility |
| 09 | run console filtering |
| 10 | debugging two services at once |
| 11 | compound run configurations |
| 12 | bookmarks as a map of the saga path |
| 13 | query console against the read model |
| 14 | Endpoints tool window |
| 15 | Profiler executor |
| 16 | Local History |
| 17 | Annotate / git blame |

Action ids drift between releases — verify each against the target build
(**Help → Find Action**, *include disabled actions*) before trusting a
detector. The current file is written against IDEA 2026.2.1 Ultimate
(262.9437.185) and unverified at runtime.

---

## 7. Milestones

1. **M1 — board in a tool window.** ✅ Gradle scaffold, JCEF tool window,
   built-in-server URL, `writeDoc` persistence.
2. **M2 — navigation and run.** ✅ `openFile` with index-based resolution,
   `run` into the IDE terminal.
3. **M3 — lesson engine.** ✅ Six detector types, lesson panel, skip, progress
   file, experiment chooser in the tool window title bar.
4. **M4 — content and static verification.** ✅ 21 lessons across the 17 steps,
   all trigger ids checked against the installed IDE by
   `ide-trainer/tools/audit_triggers.py`. Two were wrong and are fixed: the
   profiler has no executor id in this build (now the `Profiler` tool window),
   and `Services` is registered in code rather than XML, so the audit knows
   about the code-registered ids too.
5. **M5 — prove it runs.** ✅ mostly. 36 tests, including engine tests that
   publish the real platform events on the real message buses, and a server
   test that serves LedgerFlow's actual board. The plugin loads in a real IDE
   (`Loaded custom plugins: Trainer Board (0.1.0)` in the sandbox log) and the
   JetBrains Plugin Verifier reports **Compatible** against IU-262.9437.185.
   ⬜ Remaining at the time: JCEF actually painting the board, which needed a
   human to open the tool window.
6. **M6 — native UI.** ✅ JCEF, the board server and the JS bridge deleted; the
   tool window is Swing on the platform's own components, with three tabs, real
   editors for code blocks, and step navigation in the title bar. 51 tests, and
   the M5 gap is closed: `StepViewRenderTest` builds all 17 real steps
   headlessly, and the Plugin Verifier reports **Compatible** against
   IU-262.9437.185 (6 deprecated and 6 experimental API usages, all but one of
   them on `ToolWindowFactory`'s own default methods). Reference / the plan /
   the traps stay in the browser page, because they live in its HTML rather
   than in the data file.

Ongoing cost: platform API churn each major release. One of the two known
offenders is now gone — JCEF moved out of the platform into a bundled plugin in
2026.2, and the plugin no longer depends on it at all. The IDE ships Kotlin
metadata 2.4.0, so the compiler cannot be older than that. The terminal API
(`TerminalToolWindowManager` / the Gen2 migration) is the next one to expect;
its call is wrapped in `runCatching` so a rename degrades the run button rather
than breaking the tool window.

---

## Appendix A — why the zero-code transports are dead

Measured on IntelliJ IDEA 2026.2.1 (build 262.9437.185), Windows.

- **Built-in web server:** `GET /api/about` → 200, but both `/api/file/*` and
  `/<project>/<file>` → generic HTML 404. Re-measured against a sandbox IDE
  with the project open and its server confirmed on 63343, so this is not "the
  project wasn't open": neither the navigation API nor static project-file
  serving exists in this build. §3 no longer depends on either.
- **`jetbrains://` protocol:** handler registered
  (`jetbrainsd.exe handleUri "%1"`), daemon logs
  `WARN JetBrainsAppUriHandling - Nowhere to forward the URI` even though the
  same log shows the live IDE (pid 11140) registering a URL-handler sink
  earlier. The handler also logs the URI stripped of its query string, so
  `project=`/`path=` may never arrive at all.

## Appendix B — helper server, not built

The earlier recommendation: ~60 lines of Python stdlib serving the board with
`POST /open` and `POST /run`. Superseded — the plugin does both, and puts
commands in the IDE's own terminal rather than a subprocess. Worth resurrecting
only for a machine that will not run the plugin. If ever built: bind 127.0.0.1
only, random token in the URL, never accept a command from anywhere but the
page's own blocks, because `POST /run` is arbitrary local code execution.
