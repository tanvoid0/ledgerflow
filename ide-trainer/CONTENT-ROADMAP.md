# Content roadmap: which paths are enriched, and what enriching one means

**Last measured:** 2026-08-30 · regenerate with `python ide-trainer/tools/step.py check`

## Where things stand

```
path                   steps  tasks  blocks  checks   why  lessons  setup  plan  cheats
---------------------------------------------------------------------------------------
career-plan                5     26       0       0     0        0  False False   False
dsa-interview             19    175       0       0     0        0  False False   False
git                       11     55      55      20    28       14   True  True    True
java-professional         39    312       0       0     0        0  False False   False
ledgerflow                21    106     106      68   106       25   True  True   False
spring-professional       72   1002       0       0     0        0  False False   False
```

`blocks`/`checks`/`why` count **tasks** carrying that field; `lessons` counts IDE
lessons across the path.

All six use the same format and render through the same panel — a bare
checklist is a supported shape, not a broken one, and
`StepViewRenderTest.testEveryShippedPathBuilds` builds every step of every path
to keep it that way. The four zero rows simply do not use the optional fields,
so **the IDE has nothing to action in them**: no Run/Copy/Open/Create/Patch (no
`blocks`), no Verify (no `check`), no Setup tab content, no lesson pop-ups, no
Cheats tab.

## What each field buys

| field | where | what the dock does with it |
|---|---|---|
| `blocks[]` | task | a read-only editor per block, with **Run** (shell), **Copy**, **Open**/**Create**/**Update to this** (`file`), **Patch in** (`paste`), **Review**/**Deep Review** (`file`/`paste`) |
| `check{run,expect}` | task | **Verify** — runs the command, exit 0 ticks nothing but reports; a `#`-only `run` renders as an observation note instead |
| `why` / `how` | task | the two lines under the task; `why` is the one that stops a task being cargo-culted |
| `lessons[]` | step file | armed while that step is open; an IDE action/tool-window/breakpoint/file event settles it |
| `newIdeas[]` | step | the term/plain-English pairs above the task list |
| `proof`, `doneWhen`, `estimate` | step | the step's close-out; `estimate` also feeds the Map's burn-up |
| `services`, `infra` | step | the Map's dependency links — derived, nothing else provides them |
| `setup{}` | curriculum.json | the Setup tab: per-tool check command with a **Verify** button |
| `plan.milestones[]` | curriculum.json | milestone rollups in progress and on the Map |
| `cheatsheet.json` | path folder | the Cheats tab: command templates with `{hole}` fields, assembled and copyable |

## Priority, and what "enriched" means per path

Work is per step file, so any of these can stop half-done without leaving the
path broken.

### 1. dsa-interview (19 steps, 175 tasks) — best value per edit
Every task is already an exercise; it just has no runnable half.
- `blocks`: one `file` block per exercise skeleton (the class under test), one
  `shell` block running that single test.
- `check`: the test command, so **Verify** answers "did I actually solve it".
- `lessons`: the debugger (conditional breakpoints on the failing input), live
  templates, **Ctrl+Shift+F** structural search, and the profiler for the
  complexity claims.
- `setup`: JDK + the test runner. `cheatsheet.json`: the complexity/idiom table
  that currently lives in nobody's head.

### 2. spring-professional (72 steps, 1002 tasks) — by stage, never wholesale
A thousand tasks is not one job. Enrich the stage being worked through, in the
week it is worked through.
- `blocks`: mostly `paste` (a bean, a config property) and `shell` (`./mvnw
  -Dtest=…`), plus `file` for the classes a step introduces.
- `check`: an actuator curl or a single test per step, not per task.
- `lessons`: Endpoints tool window, Spring beans diagram, Ctrl+T update, the
  HTTP client scratch, run configurations per profile.

### 3. java-professional (39 steps, 312 tasks)
- `blocks`: `file` blocks per language feature, and `note` blocks where the
  answer depends on JDK version.
- `check`: `java --version` guards, then a `jshell`-runnable snippet where the
  point is the output.
- `lessons`: decompiler, bytecode viewer, JFR/profiler, Refactor→Extract.

### 4. career-plan (5 steps, 26 tasks) — probably stays a checklist
Mostly off-IDE (outreach, applications, mock interviews). `blocks` would be
theatre. Worth adding at most: `why` lines, and `estimate` so it shows on the
Map.

## Ledgerflow and git: what is still missing

- **ledgerflow** has no `cheatsheet.json`. The docker/kafka/psql one-liners
  scattered through its steps are exactly what the Cheats tab is for.
- **git** has `why` on 28 of 55 tasks and `check` on 20; stage C and D tasks
  are the thin ones. Its `how` count (3) is fine — `how` is for when the
  *method* is non-obvious, not for every task.
- Neither uses `services`/`infra` outside ledgerflow, so only ledgerflow's Map
  draws dependency links. That is correct: git has no runtime system.

## How to do the work

```bash
python ide-trainer/tools/step.py add-task dsa-interview 03 "Trace the window invariant out loud"
python ide-trainer/tools/step.py new dsa-interview A "sliding window drills" --tasks 4
python ide-trainer/tools/step.py check          # keys, ids, files, and the table above
python ide-trainer/tools/audit_triggers.py "<IDE path>" ide-trainer/experiments/dsa-interview
```

Rules that matter while editing (full argument in
[ADR-003](ADR-003-content-layout.md)):

- Edit prose freely. Ticks follow the task's `key`, so inserting, reordering
  and rewriting are all safe.
- **Never** reuse a `key` for different work, and never renumber a step `id` —
  those are the only two ways left to make progress lie.
- A placeholder key (`task-1`) may be renamed while the task is still empty;
  once anyone could have ticked it, keep it.
- Lessons go in the step file they teach, and do not repeat their `step`.
- Never hand-edit `progress.json` or `trainer.db`.
- Verify a new trigger id against an installed IDE before trusting it; a
  renamed id silently never fires.
