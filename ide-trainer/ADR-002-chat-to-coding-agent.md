# ADR-002: What remains between the Chat tab and a full coding assistant

**Status:** Proposed
**Date:** 2026-08-29
**Deciders:** Benson

## Context

The Chat tab ([Chat.kt](src/main/kotlin/io/tanvoid0/codecraft/ui/Chat.kt)) is
already an agent, not a text box. What exists today:

- **Two backends.** Ollama runs a local tool loop in-process
  ([AgentLoop.kt](src/main/kotlin/io/tanvoid0/codecraft/AgentLoop.kt) +
  [AgentTools.kt](src/main/kotlin/io/tanvoid0/codecraft/AgentTools.kt)):
  `read_file` / `list_dir` / `search` / `run_command`, capped at 8 rounds.
  Agent Platform's Coder loops server-side with its own richer tool set
  (including `write_file`), one HTTP call per turn.
- **Real permissions, one backend.** Every gated local call passes
  `Watcher.approve` — run once / always allow (session-only) / deny. The
  server-side loop cannot be gated from here (PROGRESS.md, Blocked).
- **Modes** (Chat / Plan / Code manual / Code auto), **@-file mentions**
  with fuzzy picker, **session history** in SQLite, **Stop** button, tool
  activity echoed live into the transcript.
- **Patch machinery held apart from the agent.** `Ide.patchFile` /
  `updateFile` / `PatchPreview` can already write any file as one undoable
  command behind a diff-with-Apply dialog — but only curriculum blocks reach
  it. The agent has no edit tool *by design*: "a model rewriting files
  unattended is the one thing this plugin has always refused."

That refusal is the crux. continue.dev and Claude Code are defined by the
loop *edit → run → read the failure → edit again*. Without an edit tool the
local agent can diagnose but never fix; with the Coder backend it can fix but
nothing here can gate it. Everything else remaining is feature work; this is
the one standing decision.

## Decision

**Give the local loop an edit tool, gated by the diff machinery that already
exists.** The stance shifts from "the model never edits" to "no edit lands
without the same review a curriculum patch gets":

- **Code (manual):** each `edit_file` call opens `PatchPreview` — the learner
  sees the exact diff and clicks Apply or Cancel, same as "Patch in" today.
- **Code (auto):** the edit applies without a dialog, but still as one
  undoable `WriteCommandAction` per file — Ctrl+Z and git remain the net.
- **Chat / Plan:** unchanged — no tools / read-only, enforced by not handing
  the model the means.

Remaining work, in order (each item is shippable alone):

1. **`edit_file` tool** — new gated tool in `AgentTools`; model sends
   `{path, old_string, new_string}` (exact-match replace, refused on zero or
   multiple matches — same honesty rules as `Patch.parse`). Routed through
   `PatchPreview` in manual, straight to a `WriteCommandAction` in auto.
   `write_file` (whole file, for new files) rides the existing
   `Ide.createFile` / `updateFile`.
2. **`grep` tool** — `search` matches file *paths* only; an agent fixing a
   bug needs content search. `Ide.runCapturing` + the IDE index both work;
   plain text search over `ProjectFileIndex` is the no-new-deps version.
3. **Editor context for free** — prepend the active file path, selection,
   and current-file diagnostics (`DaemonCodeAnalyzer` highlights) to the
   turn, the way continue.dev sends the active file. Kills most manual
   @-mentioning.
4. **Streaming** — Ollama's `/api/chat` streams NDJSON; render
   token-by-token into the transcript instead of a reply landing whole.
   Cosmetic but it is half of what makes those tools *feel* alive.
5. **Project rules file** — read `ide-trainer/RULES.md` (or the project's
   own `CLAUDE.md`) into the system prompt each session, so per-project
   conventions reach the model without retyping.
6. **Context budget** — history is replayed verbatim every turn and
   `maxRounds=8` is the only ceiling. Add oldest-turns truncation at a
   char budget, and raise `maxRounds` for Code modes (an edit-build-fix
   cycle burns rounds fast).
7. **Markdown transcript** — `JBTextArea` shows plain text; code blocks in
   replies deserve at least a monospace/copy affordance. A full editor-pane
   renderer is the upgrade path, not the first step.
8. **Agent Platform approval (blocked, unchanged)** — the SSE
   `/api/v1/coder/chat/approve` route still is not spoken here, so
   server-side gating stays impossible until it is wired or agent-platform
   grows a polling resolve. Not on this critical path: with items 1–2 the
   *local* backend covers the coding-assistant job end to end.

## Options Considered

### Option A: Grow the local loop; edits gated by the existing diff preview *(chosen)*

| Dimension | Assessment |
|-----------|------------|
| Complexity | Med — one new tool + wiring; preview, undo, resolve all exist |
| Safety | High — every manual edit is a shown diff; auto is undoable + git |
| Fit | High — keeps the plugin's one line (nothing lands unseen) intact |
| Dependencies | None new |

**Pros:** reuses `Patch`/`PatchPreview`/`Ide` wholesale; permission model
already proven on `run_command`; works fully offline.
**Cons:** local models are the ceiling — a 7B coder model will misuse
`edit_file` more than Claude would; manual mode means many dialogs on a
multi-file change.

### Option B: Lean on Agent Platform's Coder for everything agentic

| Dimension | Assessment |
|-----------|------------|
| Complexity | Low here, high there — client is thin, gating needs their SSE |
| Safety | Low from this side — server approves itself in auto, ungateable |
| Fit | Poor — plugin becomes a dumb terminal to an unauditable loop |
| Dependencies | agent-platform running, and its team-template bug fixed |

**Pros:** `write_file`, `repo_map` etc. already exist there; zero tool code
here. **Cons:** the whole Blocked section of PROGRESS.md; no diff preview
possible; edits land outside the IDE's undo.

### Option C: Embed an existing engine (shell out to Claude Code / continue headless)

| Dimension | Assessment |
|-----------|------------|
| Complexity | Med — process management, transcript bridging |
| Safety | Their permission model, not this plugin's diff-gate |
| Fit | Poor — the trainer exists to *be built*, not to wrap a competitor |
| Dependencies | External CLI installed and licensed per machine |

Rejected: defeats the point of the exercise and forfeits the diff-preview
line that makes this plugin trustworthy.

## Trade-off Analysis

The real trade is **Option A's model ceiling vs Option B's gating ceiling**.
A weak local model with strong gating fails safe (bad edit → cancelled
dialog); a strong remote loop with no gating fails silent (bad edit →
already on disk). The plugin's identity is the diff gate, so A wins. B stays
available as-is for users who accept server-side trust; it improves the day
the SSE approve route is wired (item 8), which this ADR leaves blocked
rather than building around.

## Consequences

- The "model never edits" absolute becomes "model never edits *unseen*" —
  ADR-recorded, so the softening is deliberate, not drift.
- Items 1–3 make the local backend a genuine continue.dev-class assistant;
  4–7 are polish that can land in any order.
- `AgentLoopTest`'s scripted-model pattern extends to `edit_file` (refusal,
  no-match, multi-match, applied) without a real project.
- Skipped on purpose, say so if wanted: inline tab-autocomplete (separate
  feature, not chat), embeddings/codebase indexing (grep + fuzzy match
  suffice at this repo scale), MCP tool surface, persisted allowlists.

## Action Items

1. [x] `edit_file` (+ `write_file`) in `AgentTools`, gated through
   `PatchPreview` (manual) / undoable write (auto); tests per `AgentLoopTest`.
   Note: edits deliberately do **not** go through `needsApproval`. That path
   shows the command dialog; an edit is approved by looking at its diff, which
   only `AgentTools` can compute. Routing both would prompt twice for one
   change, so `applyOrAsk` owns the edit gate (`Ide.confirmEdit` / `applyEdit`).
2. [x] `grep` content-search tool — matches come back as `path:line: text`,
   skipping binaries and files over 500KB, capped at 100 hits with the cap
   announced so a truncated list cannot read as a complete one.
3. [x] Editor context. "Add Selection to Chat" (`AddSelectionToChatAction`,
   Ctrl+Alt+L / editor popup) attaches `path:from-to` + code to the next
   message; `Ide.editorContext` + `editorPreamble` now also prepend the open
   file, the caret line, and the IDE's own warnings-and-above for that file to
   every turn. Sent in front of the question, never stored in history — it is
   true of this turn only.
4. [x] Streaming Ollama replies into the transcript. Reads NDJSON off an
   `InputStream` so Stop closes the socket mid-reply, rather than only taking
   effect between tool rounds.
5. [x] Rules-file injection (`rulesPreamble`: first of
   `ide-trainer/RULES.md`, `RULES.md`, `CLAUDE.md` that has content, capped at
   4k, re-read each turn); history truncation (`trimHistory`, 24k chars,
   whole turns only, newest always kept); `maxRounds` raised to 20 for Code
   modes — an edit-build-fix cycle spends a round per step and 8 ran out
   mid-repair, which reads as a wrong answer rather than a stopped one.
6. [x] Transcript in the editor's own font, so fenced code and tool output
   line up. One font for the whole transcript, not per-block — a real
   Markdown renderer needs an editor pane, which stays the upgrade path.
7. [x] Agent Platform approval — **done, both sides.** `Reviewers.agentChat`
   now returns an `AgentTurn` carrying the `pending_call` instead of reporting
   it as prose and giving up; `ChatPanel.resolvePending` answers it through
   `POST /coder/chat/approve/send` and loops until the turn answers or the
   round ceiling stops it. Both backends share one gate
   (`ChatPanel.approveCommand`) and one session allowlist, so Code (manual)
   means the same thing whichever loop is running. A refusal is sent, not
   dropped — the server records it as the tool result, which is what stops the
   model retrying the same command. Covered by `AgentApproveTest`.

   The server work this needed, in agent-platform:
   agent-platform now serves `POST /api/v1/coder/chat/approve/send`, a
   plain-JSON twin of the SSE `/approve` (both share one `run_approval`, so
   they cannot drift), and `0008_timestamp_columns_to_text` repairs the
   Alembic-era `TIMESTAMP` columns that made `POST /api/v1/teams` 500 — the
   two things this item was waiting on. What is left is this side: read
   `pending_call` off the `/send` reply, show the same run-once /
   always-allow / deny dialog the Ollama path uses, and POST the verdict.
   Then Code (manual) means the same thing on both backends.
