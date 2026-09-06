# ADR-003: Per-step content files, keyed tasks, no board page

**Status:** Accepted
**Date:** 2026-08-30
**Supersedes:** ADR-001 (placement rule kept; append-only contract dropped)
**Deciders:** Benson

## Context

ADR-001 accepted two things that have now both become the problem:

1. **One data file per path.** `ledgerflow.json` is 106 tasks in one file;
   `spring-professional.json` is 1002. Editing one subtask means loading the
   whole file — for a human in an editor, and for an agent that has to hold it
   in context to change three lines of it.
2. **Positional tick keys** (`stepId:taskIndex`). This forced an append-only
   contract: never insert, never reorder, never delete, or every tick after
   the edit point silently attaches to the wrong task. A rule that says "you
   may not edit the content" is a bad rule for content that is meant to grow.

The browser board (`ledgerflow.html`) was the reason the data file had to stay
one self-describing document, and the reason `progress` lived inside it. The
plugin renders everything the board did, so the board is dead weight that
constrains the format.

## Decision

### 1. The board is gone

`ledgerflow.html` and `ledgerflow.json` are deleted, along with the manifest's
`board` and `data` fields, the "Open Board in Browser" action, and the
two-writer reconcile against a browser. The plugin is the only reader and the
only writer.

### 2. One folder per path, one file per step

```
experiments/<path>/
  experiment.json          {"name": …, "cheatsheet": …}    — tiny, rarely edited
  curriculum.json          project, stack, setup, plan, stages → step file names
  steps/03-branches.json   one step: its tasks, and the IDE lessons it teaches
  cheatsheet.json          optional quick-command reference
  progress.json            ticks — written by the plugin, never by hand
  trainer.db               ticks with timestamps, lesson state, check results
```

`Content.load()` assembles the same `{"curriculum": …}` object the plugin
already understood, so nothing downstream changed shape.

> **Since superseded by the 2026-08-30 rearchitecture** (see
> [REARCHITECTURE-PLAN.md](REARCHITECTURE-PLAN.md), phase 1): that wrapper was
> the browser board's format, and with the board gone it was parsed once into a
> tree and then re-parsed into the typed model. `Content.load()` now returns a
> typed `Curriculum` directly. The file layout this ADR decides is unchanged.

Two consequences worth naming:

- **Editing one task opens one small file.** That is the whole point.
- **IDE lessons live with their step**, not in a separate list keyed by step
  id. A lesson file that says which step it belongs to is a second place to
  get it wrong; the step file already knows.

### 3. Authored content and recorded progress are separate files

`progress.json` holds only derived state. A content edit cannot clobber a
tick, and a tick cannot reformat authored prose. `ProgressCompatibilityTest`
rebuilds the file from the curriculum plus the tick set and demands the same
bytes, so the derivation stays honest.

### 4. Ticks are keyed by task, not by position

Every task carries a `key` — a slug, unique within its step:

```json
{ "key": "create-branch-commit-switch-back", "do": "Create a branch, …" }
```

A tick is `stepId:key`. Tasks may now be **inserted, reordered, rewritten and
deleted freely**; a tick follows its task. A deleted task's tick is orphaned
and simply ignored, which is the honest outcome.

Keys are mandatory in shipped content and pinned by a test over every path. A
task with no key falls back to its position — the old behaviour, so a
half-written step still loads — but that fallback is a bug waiting to happen,
not a supported way to author.

**Still never do:** reuse a key for different work, or renumber a step id.
Those are the only two ways left to make a tick lie.

## Options Considered

**Split by step, keys per task (chosen).** Small diffs, safe edits, one code
path. Costs a migration and a slightly larger tree.

**Keep one file, add keys.** Fixes progress, not editing. The 1002-task file
is still a 1002-task file.

**Split by step, keep positional ticks.** Fixes editing ergonomics but leaves
the trap that made ADR-001 forbid editing in the first place. Half a fix.

## Consequences

- ADR-001's placement rule stands: a subject applied to an existing project's
  code is a stage in that path; otherwise it is a new folder under
  `experiments/`.
- ADR-001's append-only contract is void. Edit content freely; keep keys
  stable.
- The one-time migration carried every existing tick from
  `stepId:index` to `stepId:key`, in `progress.json` and in each `trainer.db`
  (`tick` and `event` rows). Nothing was lost — 16 LedgerFlow ticks in, 16 out.
- New paths need no HTML and no monolith: a `curriculum.json` and a `steps/`
  folder is a complete path.
