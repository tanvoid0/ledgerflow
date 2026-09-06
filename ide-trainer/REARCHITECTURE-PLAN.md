# Rearchitecture plan: data, viewing, IDE actions

**Status:** Phases 1–4 done, phase 5 outstanding
**Date:** 2026-08-30
**Scope:** ide-trainer plugin internals only. No content format changes — ADR-003's
layout (per-step files, keyed ticks) stands untouched, and so do all 128 tests'
observable behaviours.

## Where things stand (the review)

```
disk                          memory                        screen
────                          ──────                        ──────
curriculum.json ─┐
steps/*.json ────┼─ Content.load ─→ JsonObject tree ─→ Board.curriculum
                 │                  (board-era shim)    ─→ typed Curriculum
progress.json ───┼─ Board.ticksFrom ──┐
trainer.db ──────┼─ TrainerDb ────────┼─→ CurriculumStore ─→ 8 views, each
                 │   (reconcile, 3 branches)               hand-poked by
chats.db ────────┴─ TrainerDb again, inside ui/Chat.kt     Trainer.select()/
                                                           onTick()/refreshAll()
```

Five findings, ordered by how much they cost day to day:

1. **Content is parsed twice.** `Content.load()` assembles a raw `JsonObject`
   tree shaped `{"curriculum": …}` — the format the deleted browser board
   understood — and `Board.curriculum()` then re-parses that same tree into the
   typed `Curriculum` classes. The tree survives in `CurriculumStore.root`
   where its only remaining job is the `loaded` flag. The shim outlived the
   board it existed for.

2. **`Board` is a dead name holding live code.** The pure half of the system —
   progress math (`key`, `doneCount`, `reached`, `buildProgress`), JSON
   plumbing, `gson` config — lives as an `object Board` *inside*
   `CurriculumStore.kt`, welded to the stateful, EDT-bound, VFS-writing half in
   one 395-line file. The class's own doc comment calls it "the pure, headless
   half"; the file layout disagrees.

3. **One `TrainerDb` class, two databases, all tables everywhere.** `migrate()`
   creates the chat/task/memory tables in every per-experiment `trainer.db`
   (where they stay empty forever) and the tick/lesson tables in the
   per-project chat database. Worse: `CurriculumStore` and `LessonEngine` each
   open their *own* connection to the same `trainer.db` file, safe only by the
   unenforced convention that both write on the EDT (README lists this as a
   known ceiling).

4. **Views are choreographed, not subscribed.** `store.onChange` exists but has
   exactly one listener; every other refresh is `Trainer` hand-calling
   `stepView.show()`, `stepsTree.refresh()`, `mapView.refresh()`,
   `setupView.show()`, `lessons.render()`, `lessonsBar.refresh()` in the right
   combination from four different methods (`openExperiment`, `select`,
   `onTick`, `refreshAll`). Adding a view means finding and editing all four;
   forgetting one is a silent stale panel. `TrainerToolWindow.kt` is 497 lines
   of composition root + choreography + two inner panel classes.

5. **`ui/Chat.kt` (954 lines) is the biggest layering violation.** It opens its
   own `TrainerDb` connection, constructs `AgentTools` and `AgentLoop`, calls
   `Reviewers` HTTP functions, owns mode state, history trimming and the
   approval gate — all inside a Swing class. Everything ADR-002 shipped landed
   in the UI file. The parts that are hardest to get right (the shared
   approval gate, round budgets, history truncation) are exactly the parts
   Swing makes untestable here.

And one that costs less but grates: `Ide.kt` is a 628-line grab-bag — file
resolution, patching, terminal, process exec, notifications, action dispatch,
reviewer HTTP wrappers, and the agent's edit gate — with the EDT-vs-pooled
contract of each method recorded only in comments.

## What deliberately stays

Reviewed and kept, so nobody "fixes" them later:

- **The progress.json ↔ trainer.db reconcile.** Three branches, each pinned by
  `StoreDbSyncTest`, and the file is what seeds a fresh clone. Two writers is
  the feature, not the bug.
- **The all-nullable `Curriculum` model.** Gson allocates with Unsafe and skips
  constructors, so non-null fields would be lies. Switching serializers to fix
  nullability is a dependency change for an aesthetic gain — no.
- **Buttons calling `ide.*` in DSL closures.** The `Ide` methods *are* the
  command layer; a command abstraction between a one-line closure and a
  one-line method is a layer with no job.
- **The painted Map.** One paint pass is why the dock stays responsive; it is a
  picture on purpose.
- **`TrainerApi`, the reviewers, `Patch`.** Already headless, already tested,
  already the right shape.

## The plan

Five phases. Each is shippable alone, each ends green on `./gradlew test`,
and later phases do not depend on earlier ones being merged — they just touch
less if they are. Order is by value per line of diff.

### Phase 1 — parse once: typed model straight from disk

**What:** `Content.load()` returns `Curriculum`, not `JsonObject`. Parse the
`curriculum.json` spine into the typed skeleton, parse each step file directly
into `Step` (`gson.fromJson(text, Step::class.java)`), stitch stages and stamp
`Step.stage` in one pass. Delete the `{"curriculum": …}` wrapper, delete
`Board.curriculum`, delete `CurriculumStore.root` (`loaded` becomes
`curriculum != null`).

Move the surviving pure functions out of `object Board` into a new
`Progress.kt` (`key`, `doneCount`, `reached`, `buildProgress`, `document`,
`ticksFrom`, `now`) plus the `gson` instances. The `Board` name dies with the
board. `progress.json` is still *written* via the Gson tree API — it is output,
the tree is fine there.

**Touches:** `Content.kt`, `CurriculumStore.kt`, `TrainerApi.kt` (three
`Board.` call sites), `Experiment.kt` (`Content.lessons`), tests that name
`Board`.

**Pinned by:** `ProgressCompatibilityTest` (byte-identical progress.json),
`StepViewRenderTest` (all 21 real steps still build), `StoreDbSyncTest`,
`ExperimentTest`.

**Risk:** low. Behaviour-preserving refactor with the strongest test coverage
in the repo sitting on top of it.

### Phase 2 — one schema per database, one connection per file

**What:** split `TrainerDb` into:

- `ProgressDb` — `tick`, `event`, `lesson`, `check_run`; opened per experiment.
- `ChatDb` — `chat`, `chat_turn`, `task`, `memory`; opened per project.

Each `migrate()` creates only its own tables. The 30 lines of JDBC plumbing
(`update`/`query`/`transaction`/`longOrNull`) move to one shared internal
helper. Existing `trainer.db` files keep their orphan chat tables — harmless,
no migration, SQLite does not care.

Then close the two-writers ceiling: `LessonEngine` stops opening its own
connection and writes lesson state through the store (which already exposes
`lessonStates()` — it gains `setLesson()`). One file, one connection, one
owner; the "safe because EDT" comment becomes structurally true.

**Touches:** `TrainerDb.kt` (→ two files + helper), `LessonEngine.kt`,
`CurriculumStore.kt`, `ui/Chat.kt` (opens `ChatDb` instead), `TrainerDbTest`.

**Pinned by:** `TrainerDbTest` (split alongside), `StoreDbSyncTest`,
`LessonEngineTest`.

**Risk:** low. Mechanical, and the engine's writes already run on the EDT.

### Phase 3 — viewing: views subscribe, Trainer stops choreographing

**What:** a small `Session` class owns what `Trainer` currently juggles —
`experiments`, `experiment`, `store`, `selected` — and fires typed events on a
plain listener list (no message bus, no framework; the same mechanism
`store.onChange` already is):

```kotlin
sealed interface SessionEvent {
    data class ExperimentOpened(val experiment: Experiment?) : SessionEvent
    data class StepSelected(val step: Step?) : SessionEvent
    object Ticked : SessionEvent          // store.onChange, rebroadcast
    object Reloaded : SessionEvent        // VFS change / gear reload
}
```

Each view subscribes once at install and maps events to its own refresh:
`StepView` rebuilds on `StepSelected`, header-only on `Ticked`; the tree and
map redraw on everything; `SetupView` only on open/reload. Reads stay pulls
(views query the store directly — that part was never the problem), and the
checkbox keeps calling `store.setDone` — the event comes back around through
the store's listener.

`Trainer` shrinks to a composition root: build views, wire the VFS and
tool-window listeners, register title actions. The two inner classes
(`LessonsBar`, `LessonsPanel`) move to `ui/Lessons.kt`. Target:
`TrainerToolWindow.kt` under ~200 lines, and adding a view means writing the
view, not editing four refresh paths.

**Touches:** `ui/TrainerToolWindow.kt` (heavily), one small `Session.kt`,
`ui/Lessons.kt` (new, moved code), each view gains a `subscribe(session)`.

**Pinned by:** `StepAdvanceTest`, `StepViewRenderTest`, `RoadmapTest`, plus a
manual `runIde` pass for the things only eyes catch: focus on tick, resume
step, tab restore, complete-step notification ordering (`wasComplete` moves
into `Session`).

**Risk:** medium — the subtle behaviours are focus and ordering, not data.
This is the phase to do in one sitting and eyeball in a sandbox IDE.

### Phase 4 — chat: pull the agent out of the Swing class

**What:** extract a headless `ChatController` from `ui/Chat.kt`. It owns:
`ChatDb`, `AgentTools`, `AgentLoop`, the `Reviewers` chat calls, mode state
(Chat / Plan / Code manual / Code auto), history persistence and trimming, and
the approval gate both backends share. It talks back through a small callback
surface (`onToken`, `onToolActivity`, `onTurnDone`, `askApproval`) — the same
inversion `LessonEngine` already uses toward the lessons panel.

`ui/Chat.kt` keeps what is actually Swing: transcript rendering, the input
row, the @-file picker, history list, Stop button — and becomes a thin skin
over the controller.

**Payoff:** the logic ADR-002 cares most about — one approval gate meaning the
same thing on both backends, round budgets, truncation — becomes testable in
the `AgentLoopTest`/`AgentApproveTest` style without a Swing fixture, and the
954-line file roughly halves. This is the largest single extraction in the
plan and the highest-value one.

**Touches:** `ui/Chat.kt`, new `ChatController.kt`, `AgentApproveTest` (moves
against the controller instead of reaching into the panel).

**Risk:** medium. Pure code motion plus a callback seam; the tests that exist
for approval and the loop keep the behaviour honest.

### Phase 5 — IDE actions: split `Ide` by concern, make threading checkable

**What:** `Ide.kt` becomes three classes with one job each:

- `ProjectFiles` — `resolve`, `openFile`, `createFile`, `updateFile`,
  `patchFile`, `reveal`, `PatchPreview`, plus the agent's `confirmEdit` /
  `applyEdit`. Everything that names a file; every write behind a diff or an
  undoable command.
- `Shell` — `runInTerminal`, `runCapturing`, `verify`. Everything that starts
  a process.
- `Ide` — what remains: `runAction`, `runShortcut`, `openToolWindow`, `copy`,
  `say`/`status`/`notify`, the two review wrappers, `editorContext` /
  `openFiles`. Small enough to stop being a grab-bag.

No facade over the split — call sites update mechanically (~30, all in `ui/`
and `AgentTools`). And every public method across the three gets the
platform's threading annotations (`@RequiresEdt` /
`@RequiresBackgroundThread`), so the contract the comments currently carry is
one the IDE's inspections can actually check.

**Touches:** `Ide.kt` (→ three files), `ui/Blocks.kt`, `ui/StepView.kt`,
`ui/Tabs.kt`, `ui/Cheats.kt`, `AgentTools.kt` — import/receiver changes only.

**Risk:** low, and lowest urgency: do it last, or skip until `Ide.kt` next
grows.

### Not scheduled, noted

- **Naming drift.** Package `io.tanvoid0.codecraft`, notification group
  "Codecraft", plugin presented as "Trainer", README claims
  `io.ledgerflow.trainer`. Unifying is a big mechanical diff for zero
  behaviour; do it only if it starts to hurt (e.g. publishing the plugin).
  If done: one rename commit, nothing else in it.
- **`TrainerApi.onEdt` uses `invokeAndWait` from the HTTP thread.** Fine while
  handlers are cheap; would deadlock only if the EDT ever blocks on the API.
  Leave it, remember it.

## Order and size

| phase | what | size | risk | state |
|---|---|---|---|---|
| 1 | typed load path, `Board` → `Progress.kt` | ~half day | low | **done** |
| 2 | `ProgressDb`/`ChatDb`, one connection | ~half day | low | **done** |
| 3 | `Session` events, one render pass | ~1 day | medium | **done** |
| 4 | `ChatController` out of `Chat.kt` | ~1–2 days | medium | **done** |
| 5 | `Ide` split + threading annotations | ~half day | low | outstanding |

Gate for every phase: `./gradlew test` green, `python tools/step.py check`
clean, and for 3–4 a `runIde` eyeball. If any phase turns out to need a
behaviour change to land, that change gets its own ADR first — this plan is
refactoring only.

## What actually shipped

Tests went 214 → 233, all green, and `python tools/step.py check` is clean. No
behaviour was meant to change and none was observed to.

**`./gradlew verifyPlugin` fails, and did before this work started.** The
verdict itself is *Compatible*, but the task fails the build on
`[INTERNAL_API_USAGES]`: `Ide.problems` calls
`DaemonCodeAnalyzerImpl.getHighlights`, which is `@ApiStatus.Internal`. That is
the editor-context feature from ADR-002 item 3, and it is the same rule that
made this project refuse the IDE's bundled SQLite (README, Known ceilings) —
so the plugin now breaks its own stated line. Untouched by phases 1–4 and left
alone deliberately: choosing a replacement (the document's `MarkupModel`
highlighters is the usual one, and is only marginally less impl-flavoured) is a
behaviour decision, not a refactor. Worth its own fix, because until then the
README's claim that `verifyPlugin` reports Compatible is not true of the build.

| | before | after |
|---|---|---|
| `ui/Chat.kt` | 954 | 602 |
| `ui/TrainerToolWindow.kt` | 497 | 332 |
| `CurriculumStore.kt` | 427 | 245 |

New files: `Progress.kt` (`Json` + `Progress`, the pure half), `Sqlite.kt` +
`ProgressDb.kt` + `ChatDb.kt` (was one `TrainerDb`), `Session.kt`,
`ChatController.kt`, `ui/Lessons.kt`.

Two deviations from the plan as written, both deliberate:

- **Phase 3 renders centrally rather than having each view subscribe.**
  `Session` emits the four events and `Trainer.render()` handles them in one
  exhaustive `when`. Eight panels that all live in one composition root do not
  need a bus to reach each other; what they needed was for the refresh logic to
  stop being spread across four methods that each had to remember the same
  list. The `when` is compiler-checked, which the four methods never were.
- **Phase 4 leaves view state in the panel.** Busy/queue/retry/thinking are
  genuinely the panel's, so the controller owns the turn (backends, preambles,
  approvals, history, persistence) and writes the transcript through a
  five-method `ChatUi`. The panel keeps the Swing.

Three tests exist that could not have been written before:

- `SessionTest` (7) — the open/select/tick sequencing, including that a step
  reports complete exactly once and that unticking lets it complete again.
- `ChatApprovalTest` (12) — the gate ADR-002 turns on: auto runs silently, run-once
  does not stick, always-allow is remembered per command, a dismissed dialog is
  a denial, a call with no command is asked about by name, and a refusal is
  posted to the server rather than dropped.
- `ProgressDbTest` / `ChatDbTest` — the split databases, each over its own schema.

One incidental fix, not a refactor: `resolvePending` now takes the thread id
from the turn it is resolving (`turn.threadId ?: agentThreadId`) rather than
only from the field. Same value in the panel's flow, but it makes the function
answerable on its own, which is what let the approval tests reach it.

### Phase 5, if picked up later

Unchanged from the plan above, and still the least urgent of the five: `Ide.kt`
is a 628-line grab-bag (`ProjectFiles` / `Shell` / a smaller `Ide`), and its
EDT-vs-pooled contract is still carried in comments where
`@RequiresEdt` / `@RequiresBackgroundThread` would let the IDE's own
inspections check it. Nothing in phases 1–4 depends on it.
