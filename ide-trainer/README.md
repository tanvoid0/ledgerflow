# ide-trainer

An IntelliJ plugin that runs a curriculum in a tool window and teaches IDE
features at the point the curriculum needs them — the IDE Features Trainer
pattern, but pointed at your own project instead of a sandbox one.

LedgerFlow is the first **experiment**. Nothing in the plugin knows about
LedgerFlow; it reads `experiments/*/` and renders whatever curriculum it
finds there.

## Build and install

```bash
cd ide-trainer && ./gradlew buildPlugin
```

Then in IDEA: **Settings → Plugins → ⚙ → Install Plugin from Disk** →
`build/distributions/ide-trainer-<version>.zip`, **and restart the IDE** — the
tool window does not appear until you do.

Open it either way:

- **Tools → Open Trainer Board** (also findable from Search Everywhere), or
- **View → Tool Windows → Trainer**, or the ⧉ icon on the right edge.

### Not seeing it?

1. **Restart.** Installing from disk registers nothing until the IDE restarts.
2. **Check it installed:** Settings → Plugins → Installed → search *Trainer
   Board*. If it is not there, the zip was not installed; if it is there but
   greyed out, it is disabled.
3. **Right IDE?** `./gradlew runIde` starts a *separate sandbox IDE* with the
   plugin. The plugin is not in your normal IDE unless you installed the zip
   there.
4. **A project must be open.** The tool window is project-level; on the Welcome
   screen there is nothing to show.
5. The tool window is registered by the `Trainer` id and verified by
   `ToolWindowRegistrationTest`, so if all of the above hold and it is still
   missing, look in **Help → Show Log in Explorer** for an error mentioning
   `io.ledgerflow.trainer`.

For a throwaway sandbox IDE instead of installing into your own:

```bash
cd ide-trainer && ./gradlew runIde
```

The build compiles against the IDE installed at the `idePath` property in
`gradle.properties`, so lessons are verified against the exact build you run.
On another machine: `./gradlew -PidePath="/path/to/IntelliJ IDEA" buildPlugin`.

## How it fits together

```
tool window
├── tab "Step"   the step you are on: goal, new ideas, tasks with real
│                editors for their code blocks, proof, gate — and under it
│                the IDE lessons armed for that step
├── tab "Steps"  stages and steps, done counts, click to jump
├── tab "Map"    the whole course as a graph: burn-up chart, a lane per
│                stage, a node per step, and the links between them
└── tab "Setup"  the tools the curriculum assumes, each check runnable
                 and its last result remembered
```

Opening the dock puts you back in the step you were last working in, not the
first unfinished one — jumping ahead deliberately is a thing people do. The
step header says how long ago that step was started, and the Steps tab counts
what you have ticked today.

**Alt+Left** and **Alt+Right** move between steps. The shortcut is registered
on the dock rather than globally, so it cannot shadow a key the editor already
owns. A step that teaches no IDE feature drops the lessons panel instead of
keeping a third of the dock to say so.

All Swing, all the platform's own components — IDE fonts, both themes, HiDPI,
and the tool window's own tab strip and gear menu. There is no embedded
browser: JCEF in a 400px dock got none of that, needed a loopback server to
dodge CORS, and moved out of the platform into a bundled plugin once already.

A right-hand dock is narrow and always visible, which makes it a place to
**work**, not to read. The browser page this started as is gone (see
[ADR-003](ADR-003-content-layout.md)); the dock is the only reader now, which
is what let the content split into per-step files.

The **Cheats** tab is the quick reference that used to want a wide page: a
command template per row, a field per `{hole}` in it, and the assembled line
ready to copy or run. It comes from the path's own `cheatsheet.json`, so a
path without one simply has no tab content.

### The map

The **Map** tab is the overview a scrolling list cannot give: the burn-up of
tasks (what the course asks for, cumulative, against what you have ticked), a
lane per stage, and a node per step carrying its own progress bar, what it
introduces, and how many of its IDE lessons you have settled. Click a node to
work on that step.

The links are derived, because the data file has no dependency field. A step
lists the services and infra it runs on as the system *so far* — sometimes the
whole set, sometimes only what is new (`+ redis`, `all`, `grafana / tempo`) —
and nothing is ever retired, so each step is the one before it plus what it
names. That makes "where did this arrive" answerable: the selected step draws a
line back to every step that put something into the system it is still running
on, and `RoadmapTest` pins that arithmetic against the real curriculum, because
a graph that is confidently wrong is worse than no graph.

Under the lanes is the same system as a matrix: a row per service or piece of
infrastructure, a column per step, the solid cell where it arrives and a tinted
one for every step after it. Hover a row and only the steps that run on that
thing stay lit. Cells shrink with the dock and the section drops itself rather
than being squeezed into columns too thin to read; the step numbers over the
columns go first.

It is painted rather than assembled from components: seventeen cards with
progress bars, a chart and the arcs between them are a picture, not a form, and
one paint pass keeps the dock responsive while you tick tasks. Lanes flow into
as many columns as the width allows, so the same graph works in a 300px dock
and in a floated window.

### Where the content lives

One folder per path, one file per step:

```
experiments/git/
  experiment.json          {"name": …, "cheatsheet": …}
  curriculum.json          project, stack, setup, plan, stages → step file names
  steps/03-branches.json   one step: its tasks, and the IDE lessons it teaches
  cheatsheet.json          optional quick-command reference
  progress.json            ticks — written by the plugin, never by hand
  trainer.db               ticks with timestamps, lesson state, check results
```

Editing one task means opening one small file, and a tick names its task:
every task carries a stable `key` slug, and a tick is `stepId:key`. Tasks can
be inserted, reordered and rewritten without progress shifting under them —
the rule that replaced the old append-only contract. `ADR-003` has the whole
argument.

### Where progress is kept

Progress is not in the content: the files know a task is done but never
*when*, and a file rewound by `git checkout` would take the ticks with it.

So ticks and lesson state live in **`experiments/<name>/trainer.db`**, a
SQLite file holding the tick, the moment it happened, every event before it,
and what each setup check last said. That is what the dock reads to say *started 20m ago*, *6 today*, and to
reopen on the step you were actually working in rather than the first
unfinished one.

`progress.json` is written on every change too — a diffable copy of the same
ticks, and the file a fresh clone starts from. When the two disagree, the
newer writer wins:

| situation | what happens |
|---|---|
| database is empty | first run — the file seeds it |
| `progress.updatedAt` is newer than the newest event | the file was replaced (a checkout, a copy); the database adopts it |
| otherwise | the database is right; its ticks are adopted and the file is rewritten |

`StoreDbSyncTest` pins each of those three, because getting the merge wrong
loses work silently. Add `experiments/*/trainer.db` to `.gitignore` — it is
per-machine state, not curriculum.

### Progress is derived, and that is pinned

Every number in `progress.json` — the summary, the per-step counts, the plan
rollup — recomputes from the step files plus the tick set. So
`ProgressCompatibilityTest` rebuilds it from the real curriculum and asserts it
equals what is on disk, and separately asserts every task in every shipped path
has a unique key. A content edit that would quietly change what a tick means
fails the build instead.

### What the panel asks the IDE to do

| button | effect |
|---|---|
| **Run** | the block runs in the IDE's own terminal, in one reused *Trainer* tab rather than a new tab per command |
| **Copy** | the block goes to the clipboard |
| **Open** | the file the label names, resolved through the project index, opens in the editor |
| **Create** | `file` blocks only: writes the block into the file the label names, then opens it. An existing file is opened untouched, never overwritten |
| **Update to this** | `file` blocks whose file already exists: the block against the file in the IDE's diff viewer, applied only if you press Apply |
| **Patch in** | `paste` blocks: asks the local model *where* the fragment goes, shows the diff, applies it on Apply |
| **Review** | `file` and `paste` blocks: sends the block to a local Ollama model, answer in a balloon |
| **Deep Review** | the same block to an agent-platform team, which fans the review out across several agents server-side |

Copying, and opening a file the curriculum has not created yet, say so in the
status bar rather than looking like a button that does nothing.

### Patching without trusting the model with your file

A `paste` block is a fragment — half of them name no file at all ("call it from
the controller"), and where it goes is the work. **Patch in** hands that
question to the local model and nothing else:

- the model is asked *where*, never *what*. It answers `{start, end, indent,
  reason}` under Ollama's `format: json`, and the lines written are the
  curriculum's bytes verbatim, so a model having a bad day can pick the wrong
  place but cannot invent a line of code;
- a line outside the file, an `end` before its `start`, or a replace far larger
  than the snippet is refused rather than clamped, and an `indent` that is not
  whitespace is dropped — that field is the one place a model could smuggle in
  code (`Patch.parse`);
- a snippet already in the file, whatever it is indented by, is not offered
  again;
- the document changing while the model thinks abandons the patch, because the
  line numbers describe the text that was sent;
- nothing is written until you press **Apply** in the diff, and what is written
  is one undoable command.

`Patch.kt` has no IDE types in it, so those rules are tested headlessly in
`PatchTest` — including the answers a model is not allowed to give.

With no file named, **Patch in** patches whatever is open in the editor, which
is where a learner reading "call it from the controller" already is.

Neither reviewer is required — with nothing running you get a warning balloon,
not a broken panel:

| reviewer | default | set it in |
|---|---|---|
| **Review** | `http://localhost:11434/api/generate`, model `qwen2.5-coder` | Settings \| Tools \| Trainer Board |
| **Deep Review** | `http://127.0.0.1:18410`, and a team template id it has no default for | same page — `GET /api/v1/teams/` on that server lists the ids |

**Test** on that page asks each server whether it is there, before a step
depends on it. For the local model the same call fills the **Model** dropdown:
it lists what that machine has actually pulled, with the metadata Ollama
reports — parameter size, quantisation, disk size, family, when it was pulled —
because parameter size is what decides whether a snippet lands in the right
place, and a name alone does not tell you it. The list has the platform's own
speed search, so thirty pulled models are one *coder* away from the right one,
and the field is editable — pulling a model and configuring it happen in either
order, so a name this machine has never seen is a legitimate entry that **Test**
will then tell you is not installed yet,
and the agent platform's group lists its models the same way when it publishes
a catalogue (its own team templates choose which one reviews, so that list is
there to answer *what will it use*, not to be set).

Endpoint reachable but the chosen model not installed is its own line —
*"Running, but qwen2.5-coder is not installed"* with the `ollama pull` to fix
it — because that failure otherwise reads as a broken plugin. When either
server is unreachable *while you are using it*, the balloon now carries the
address it tried and points at this page, instead of only
`Connection refused: no further information`.

The chosen model is kept in `trainer-reviewers.xml` like the rest of the page,
so it survives the dialog, the project and the IDE restarting — and a model
configured on a machine where nothing is serving right now stays selected
rather than being swapped for whatever answers first.

Both take effect immediately; there is nothing to recompile and nothing to
restart. Defaults are seeded from `OLLAMA_ENDPOINT`, `OLLAMA_MODEL`,
`AGENT_PLATFORM_BASE_URL` and `AGENT_PLATFORM_TEAM_TEMPLATE_ID` if the IDE was
started with them, so an environment that already worked keeps working — and
its values are then visible on the settings page instead of being invisible.

`AGENT_PLATFORM_TOKEN` stays an environment variable on purpose and is read at
the moment of the call: the settings file is plain text, which is a worse place
for a secret than the environment.

Both reviewers are plain functions over HTTP in `Reviewers.kt` with no IDE
types in them, so `ReviewersTest` drives them against a stub server built from
the JDK's own `HttpServer` — including the poll loop, its deadline, and the
failure reason the platform reports.

The Setup tab's **Verify** runs a tool's own check command and reads its exit
code: zero is installed. It gets ten seconds and is killed if it takes longer,
so a hung probe cannot sit on the answer, and the result is remembered with
the time it was taken.

Code blocks render in read-only IntelliJ editors, with the file type guessed
from the block's label — so a `.java` block gets Java highlighting and a shell
block gets shell highlighting, out of whatever plugins the IDE already has and
with no extra dependency.

## Adding an experiment

Three files make a path. `experiments/<name>/experiment.json`:

```json
{ "name": "My Course", "cheatsheet": "cheatsheet.json" }
```

`experiments/<name>/curriculum.json` — the spine, naming step files in order:

```json
{
  "project": "My Course",
  "stack": "whatever it needs",
  "stages": [
    { "letter": "A", "name": "Foundations", "steps": ["01-first.json"] }
  ]
}
```

`experiments/<name>/steps/01-first.json` — one step, its tasks, and the IDE
lessons it teaches:

```json
{
  "id": "01",
  "title": "first step",
  "goal": "what this step is for",
  "tasks": [
    { "key": "open-a-class", "do": "Open Wallet.java without the project tree",
      "why": "the tree is the slow way",
      "blocks": [ { "label": "terminal", "kind": "shell", "body": "ls" } ],
      "check": { "run": "test -f pom.xml", "expect": "exit 0" } }
  ],
  "lessons": [
    {
      "id": "01-goto-class",
      "title": "Navigate → Class (Ctrl+N)",
      "teach": "Don't hunt in the project tree — <b>Ctrl+N</b>, type the name.",
      "trigger": { "type": "action", "id": "GotoClass" },
      "fallbackTip": "Ctrl+N (Navigate → Class)."
    }
  ]
}
```

`key` is the task's identity for progress and must be unique within the step.
A lesson does not name its step — the file it lives in already does.

Don't hand-copy that skeleton. `tools/step.py` writes it, generates keys, and
keeps `curriculum.json` in step with the folder:

```bash
python ide-trainer/tools/step.py new git B "rebasing safely" --tasks 3
python ide-trainer/tools/step.py add-task git 07 "Squash the review fixups"
python ide-trainer/tools/step.py check
```

`check` is the same invariant `ExperimentTest` pins — keys present and unique,
step ids unique, every listed file present and every present file listed —
runnable without Gradle, and `new`/`add-task` run it after writing. It also
prints what each path actually uses (blocks, checks, lessons, setup, cheats):
four of the six shipped paths are bare checklists, which renders fine but gives
the dock nothing to run or verify. [CONTENT-ROADMAP.md](CONTENT-ROADMAP.md) is
that table with the argument and the priority order attached.

### Triggers

Only lessons for the current step are armed, so a shortcut pressed in step 12
cannot retroactively tick step 01.

| type | `id` | fires when |
|---|---|---|
| `action` | an action id (`GotoClass`, `FindUsages`, `Annotate`, …) | the learner invokes that action, by any route |
| `run` | an executor id (`Run`, `Debug`) | a run configuration starts under that executor |
| `fileOpen` | a path suffix or `*.ext` | such a file is opened in the editor |
| `fileCreate` | a path suffix or `*.ext` | such a file appears in the project |
| `toolWindow` | a tool window id (`Database`, `Services`, `Maven`, `Endpoints`, `Profiler`) | that tool window is shown |
| `breakpoint` | a breakpoint type id, or `*` for any | a breakpoint is added |

`*` on its own means "any event of this kind"; `*.ext` matches an extension;
anything else matches exactly, or as a path suffix at a segment boundary.

Ids drift between releases, and a lesson pointing at a renamed id silently never
fires — which looks exactly like a learner who hasn't done the thing yet. Check
a whole experiment against an installed IDE before trusting it:

```bash
python ide-trainer/tools/audit_triggers.py
```

It reads the ids straight out of the IDE's jars. The LedgerFlow experiment's 21
triggers all pass against IDEA 2026.2.1 Ultimate (262.9437.185). Every lesson
also has a **skip** button, so a detector that cannot fire never blocks
progress — skipped is recorded as skipped, not done.

Lesson progress is written to `experiments/<name>/trainer.db`, separate from
the authored content.

## What is tested

```bash
cd ide-trainer && ./gradlew test          # 233 tests
cd ide-trainer && ./gradlew verifyPlugin  # binary compatibility — currently FAILS, see below
python ide-trainer/tools/audit_triggers.py   # 21/21 trigger ids exist
python ide-trainer/tools/step.py check       # content lint: keys, ids, files
```

The engine tests publish the real platform events (`AnActionListener`,
`ToolWindowManagerListener`, VFS creates) on the real message buses, so they
catch the failure this design is most exposed to: a listener wired to the wrong
bus, which silently never fires and looks exactly like a learner who has not
done the thing yet.

`StepViewRenderTest` builds every one of the 21 real steps headlessly — tasks,
editors, the steps tree, the setup tab. With JCEF that was impossible and "does
the tool window paint" needed a human; with Swing it is a test.

## Known ceilings

- The IDE bundles its own SQLite (195KB, natives included) but every class in
  it is `@ApiStatus.Internal`, and `verifyPlugin` fails the build over that.
  The plugin uses `org.xerial:sqlite-jdbc` instead, which is 14MB of the 14.5MB
  zip — natives for every platform, so a build made here still installs
  anywhere.
- **`verifyPlugin` currently fails**, on `[INTERNAL_API_USAGES]`: `Ide.problems`
  reads the editor's warnings through `DaemonCodeAnalyzerImpl.getHighlights`,
  which is `@ApiStatus.Internal` — the same rule that keeps the bundled SQLite
  out (below). The verdict is otherwise *Compatible*. Either replace the call
  (the document's own `MarkupModel` highlighters) or drop the diagnostics half
  of the chat's editor context; until then this gate is red.
- One connection per database file, held by the object that owns it: the
  experiment's `ProgressDb` belongs to its `CurriculumStore`, and the lesson
  engine writes lesson state through that store rather than opening a second
  connection of its own. The chat panel's `ChatDb` is a different file with a
  different schema — neither creates the other's tables.

- Detection is per-step, in-memory arming; lessons are matched on the first
  pending one. Two lessons in a step with the same trigger will tick in order,
  not by which one you meant.
- The terminal call uses `TerminalToolWindowManager.createShellWidget`, an API
  that has been renamed more than once; it is wrapped in `runCatching`, so a
  future rename degrades the run button rather than breaking the tool window.
- Rendering is proved by construction, not by pixels. A test builds every step
  and every panel, which catches the failures that matter (a broken layout call,
  a missing file type, prose the DSL chokes on) but not "it looks wrong". Run
  `./gradlew runIde` and open **View → Tool Windows → Trainer** to look at it.
- A step rebuilds its whole body when you select it, and every code block is a
  real editor — a dozen per step. Ticking a task deliberately updates only the
  header so it does not pay that cost again.
