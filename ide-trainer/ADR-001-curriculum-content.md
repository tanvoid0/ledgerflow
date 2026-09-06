# ADR-001: Where new lesson content goes, and how existing content may change

**Status:** Superseded by ADR-003 (placement rule still stands; the
append-only contract and the board page are gone)
**Date:** 2026-08-29
**Deciders:** Benson

## Context

The trainer already has a two-level content model:

- A **path** is an experiment: a folder under `experiments/<name>/` with an
  `experiment.json` naming a data file (and optionally a board page and IDE
  lessons). Five exist: LedgerFlow, DSA Interview, Java Professional,
  Spring Professional, Career Sprint.
- A **curriculum** lives inside the path's data file as
  `stages[] → steps[] → tasks[]`, with a `plan.milestones[]` overlay that
  groups step ids.

Progress is stored twice — in the data file's `progress` block and in the
per-experiment SQLite database — and both key a tick as **`stepId:taskIndex`**.
The task half of the key is *positional*. The board page (`ledgerflow.html`)
and the plugin read and write the same file; unknown keys are copied through
untouched, and every parsed field is nullable.

Two recurring questions have no written rule:

1. When a new subject arrives (Kafka is the live case): new stage in an
   existing path, or a new path?
2. What edits to existing content are safe, given positional tick keys and
   two writers?

## Decision

### 1. Placement rule: *path = project or track, stage = subject within it*

A subject gets a **new stage in an existing path** when it is learned by
applying it to that path's project. It gets a **new path** only when it has
its own arc and its own definition of done, independent of any existing
project.

**Kafka: add a stage to the LedgerFlow curriculum.** LedgerFlow is a
microservices practice project; Kafka is learned best by wiring real events
between the services already built there. A standalone "Kafka theory" path
would duplicate the project context the tasks need anyway. Concretely:

- New stage (next free letter) in `ledgerflow.json` with its own
  `youWillLearn`, steps with fresh ids continuing the existing numbering.
- One new milestone in `plan.milestones` listing those step ids.
- Optional IDE lessons keyed to the new steps in `experiment.json`.

No code changes: the plugin renders whatever the file holds.

### 2. Change rules for existing content (the append-only contract)

Because ticks are `stepId:taskIndex`:

**Always safe** (no progress impact):
- Editing any prose: titles, goals, `why`/`how`, blocks, checks, estimates.
- Appending tasks to the **end** of a step's task list.
- Adding new steps (fresh ids), new stages, new milestones, new extras.
- Additive schema: new optional keys — both readers ignore what they don't
  know, and `Board.document()` copies unknown keys through.

**Never do silently** (corrupts or lies about progress):
- Inserting, deleting, or reordering tasks within a step — every tick after
  the edit point shifts to the wrong task.
- Reusing or renumbering a step id — old ticks attach to the new content.

**Restructuring escape hatch:** when a step genuinely needs its tasks
reworked, give it a **new step id** and delete the old one. Progress for that
step resets to zero — an honest reset instead of silent corruption. The plan
milestone referencing it is updated in the same edit.

**Two-writer rule:** content edits touch only the `curriculum` block. The
`progress`/`ui` blocks stay owned by whoever saves last (existing
timestamp reconcile). Never hand-edit `progress`.

## Options Considered

### Option A: Kafka as a stage in LedgerFlow *(chosen)*

| Dimension | Assessment |
|-----------|------------|
| Complexity | Low — JSON edit only |
| Pedagogy | High — applied to services that already exist |
| Progress continuity | Full — additive, nothing resets |
| Reuse | Board, DB, IDE lessons all come free |

**Cons:** LedgerFlow file grows; Kafka progress is entangled with the
LedgerFlow arc (can't "finish LedgerFlow" without it once added).

### Option B: Kafka as its own path

| Dimension | Assessment |
|-----------|------------|
| Complexity | Low-Med — new folder, new data file, no board |
| Pedagogy | Weaker — tasks lack a real project unless one is scaffolded |
| Progress continuity | N/A — fresh |
| Reuse | Plugin renders it, but no board page, no project code to act on |

**Pros:** clean isolation, LedgerFlow arc untouched. Right choice for
subjects with no host project (this is what DSA Interview is).

### Option C: Stable per-task ids instead of positional ticks

Would make all edits safe, but requires migrating the file schema, the board
page, the plugin, and the DB in lockstep for a problem the append-only
contract already contains. Rejected as speculative; revisit only if content
churn makes honest resets frequent enough to hurt.

## Consequences

- Adding any new subject is a decision that takes one sentence: does it
  apply to an existing project's code? Stage. Otherwise? Path.
- Content authors (human or agent) follow the append-only contract; the
  restructure escape hatch is the only sanctioned way to break it.
- Task-level identity stays positional — accepted debt, documented here,
  upgrade path is Option C.

## Action Items

1. [x] Author the Kafka stage in `ledgerflow.json` (stage, steps, milestone) — stage E, steps 18–21, milestone m4.
2. [x] Add Kafka IDE lessons to `experiments/ledgerflow/experiment.json` — one per step, 18–21.
3. [x] Point content-editing agents at this ADR's change rules — see the curriculum section in `CLAUDE.md`.
