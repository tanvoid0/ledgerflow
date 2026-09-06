# Native UI — plan

The tool window currently paints `ledgerflow.html` in a JCEF browser. A web page
is the wrong thing to put in a 400px dock: wrong fonts, wrong colours in dark
theme, no keyboard integration, no Search Everywhere, a loopback server, and a
JCEF dependency that has already moved once between releases.

This replaces it with Swing and the IntelliJ Platform's own components. The
curriculum stays in `ledgerflow.json` — that file is the contract, unchanged —
and `ledgerflow.html` keeps working in a browser off the same file.

---

## 1. What the tool window is for

A right-hand dock is narrow and always visible. That makes it a **working**
surface, not a reading one. So the split is:

| surface | where | why |
|---|---|---|
| current step, its tasks, its commands | **tool window** | you touch it every few minutes |
| all steps, progress, jump to one | **tool window** | navigation, one click away |
| setup checks | **tool window** | you run each one |
| reference tables, the plan, traps, how-to-use | **browser board** | long-form, wants full width, read once a week |

Reference and Traps are not in `ledgerflow.json` at all — they are hardcoded
HTML inside `ledgerflow.html` (~230 lines). Porting them means moving them into
the JSON and bumping its schema, which then obliges the browser board to render
them from JSON too. That is content work with no UX payoff for the daily loop,
so it is **phase 5, optional**, and until then the gear menu has *Open board in
browser*.

---

## 2. Layout

```
Trainer  ⟨tool window, right dock⟩
├── title actions:  ◀ prev step · ▶ next step · ⌕ go to step
├── gear menu:      reload · open board in browser · choose experiment
│
├── tab "Step"      ← the default, where you live
│   └── OnePixelSplitter (vertical, proportion remembered)
│       ├── step view (scrolling, Kotlin UI DSL v2)
│       │   ├── header       01 · A service that runs · half a day · 6/6 ✓
│       │   ├── Goal         JBHtmlPane prose, IDE fonts, theme-aware
│       │   ├── ▸ New ideas  collapsibleGroup, one row per term
│       │   ├── tasks        checkbox · what to do
│       │   │                  ▸ expands: why · how · code blocks · check
│       │   ├── Proof        the run command + what to expect
│       │   └── Done when    the gate for this step
│       └── lesson panel     the IDE lessons armed for this step
│
├── tab "Steps"     ← navigation: stages ▸ steps tree, done counts, click to open
└── tab "Setup"     ← the five tools, each with a check command you can run
```

Every code block is a real **read-only IntelliJ editor** (`EditorTextField`)
with the file type guessed from the block's label, so a `.java` block gets Java
highlighting and a shell block gets shell highlighting — from whatever plugins
the IDE already has, with no new dependency. Each block carries `Run` (into the
IDE terminal), `Copy`, and — for `file` blocks — `Open` (navigates to the file
in the editor) and `Create` (writes the block into that file if it does not
exist yet, then opens it).

---

## 3. What gets deleted

- `BoardServer.kt` (84 lines) and `BoardServerTest.kt` — no page to serve.
- The JCEF browser, the `JBCefJSQuery` bridge injection, `window.__lfBridge`.
- `<depends>com.intellij.modules.jcef</depends>` and its Gradle `bundledPlugin`.
- `Bridge.handle` / its JSON payload parsing — the UI calls the methods directly.

`ledgerflow.html` itself is **not** touched. Its `__lfBridge` hooks become dead
code inside the IDE and keep working in a browser, which is what they already do.

---

## 4. What gets written

| file | job |
|---|---|
| `Curriculum.kt` | data model + Gson parse of `curriculum` |
| `CurriculumStore.kt` | project service: load, tick, save, watch the file |
| `Ide.kt` | `openFile` · `createFile` · `runInTerminal` · `copy` (was `Bridge.kt`) |
| `TrainerDb.kt` | ticks, lesson state and their history in SQLite |
| `ui/TrainerToolWindow.kt` | factory, tabs, splitter, title/gear actions |
| `ui/StepView.kt` | the step panel, tasks, code blocks |
| `ui/StepsTree.kt` | the navigation tab |
| `ui/SetupView.kt` | the setup tab |

`Experiment.kt` and `LessonEngine.kt` are unchanged. The lesson engine already
does the hard part and has nothing to do with rendering.

### The one rule the store must not break

`ledgerflow.json` is shared with the browser board, so the plugin must write
**exactly** the shape the board writes, or the two disagree and someone loses
ticks. That means:

- keep the parsed root `JsonObject` and replace only `progress` and `ui` —
  `schema`, `_readme` and `curriculum` are copied through untouched;
- `progress.summary` / `progress.steps` / `progress.plan` built by the same
  rules as the board's `buildProgress()`, including `currentStep` = the first
  step with an unticked task;
- pretty-print with **2 spaces and HTML escaping off** — Gson escapes `<` by
  default, and the curriculum prose is full of `<code>` tags.

There is a test that ticks a task, writes, re-reads and compares against the
board's own output for the same state. That test is the reason this is safe.

---

## 5. IDE features the native UI can now use

Free, because it is Swing: the IDE's fonts, both themes, HiDPI scaling,
accessibility, and the tool window's own tab strip and gear menu.

Bought cheaply:

| feature | how |
|---|---|
| syntax-highlighted blocks | `EditorTextField` + `FileTypeManager.getFileTypeByFileName` |
| run a command | `TerminalToolWindowManager` — the IDE's terminal, not a subprocess |
| open a file at a line | `OpenFileDescriptor` + `FilenameIndex` resolution |
| jump between steps | title actions with keyboard shortcuts, `JBPopupFactory` chooser |
| step complete | `NotificationGroupManager` balloon |
| remembered dock state | `PropertiesComponent` for the splitter, JSON for everything else |

Deliberately **not** built: a Search Everywhere contributor, a status-bar
progress widget, gutter marks, and a `FileEditorProvider` reading tab. Each is a
day of work for a feature nobody has asked for yet. The reading tab is the one
worth revisiting if Reference ever moves into the JSON.

---

## 6. Sequence

1. **Model + store, with tests.** No UI. Parse the real `ledgerflow.json`,
   tick a task, write it back, prove it matches the board byte for byte.
2. **Step view.** Header, goal, ideas, tasks, blocks, proof, gate. Delete the
   JCEF browser and the board server at the end of this step, not before.
3. **Navigation.** Steps tree, prev/next/go-to actions, gear menu.
4. **Setup tab** and the notification on step completion.
5. *(optional)* Reference and Traps into the JSON, and a full-width reading tab.

Steps 1–4 are this change. Step 5 is a separate decision about the data file.
