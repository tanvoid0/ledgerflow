# Shell

Run shell commands with the Bash tool (Git Bash / IntelliJ terminal shell), not PowerShell or cmd.exe, even though PowerShell is the platform default. Commands written for PowerShell fail or behave differently here.

# Curriculum content edits

Content lives one file per step: `experiments/<path>/steps/NN-slug.json`, listed
by `experiments/<path>/curriculum.json`. Read ADR-003-content-layout.md before
editing. The short version: every task carries a stable `key` slug and ticks are
`stepId:key`, so you may insert, reorder, rewrite and delete tasks freely — just
never reuse a key for different work and never renumber a step id. IDE lessons
live in the step file they teach. Never hand-edit `progress.json` or trainer.db.
New subjects: a stage in an existing path if it applies to that path's project,
a new experiment folder otherwise.

Scaffold and lint with `python ide-trainer/tools/step.py new|add-task|check` rather
than hand-copying a step file — it generates keys and keeps `curriculum.json` in
step with the `steps/` folder.

Enriching an existing path (adding blocks/checks/IDE lessons to a bare
checklist): read CONTENT-ROADMAP.md first — it records which paths are thin,
what each optional field buys, and the priority order.
