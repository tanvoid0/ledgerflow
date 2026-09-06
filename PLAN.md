# LedgerFlow: how this gets built and verified

Decided 2026-09-06, after a 16-agent review of the curriculum, the dock, the
plugin and three competing verification designs. This file is the working
agreement. Everything else in the repo is either code or content.

---

## Verdict

| | decision |
|---|---|
| Verification | A standalone `verify/` Maven project. Black-box. Zero imports of your code. |
| Test gate | Surefire's own `-Dtest`. No annotations, no extensions, no config files. |
| Dock Verify button | **Not built.** The loop is one terminal command. Revisit in month two. |
| Content schema | **Unchanged.** Not one new field. Ticket prose goes in `goal`. |
| The 116 solution blocks | Deleted per step, only where a test replaces them, only on behavioural steps. |
| ide-trainer | Frozen, but tracked in git. The freeze is that the daily loop contains no plugin command. |
| The plugin's chat/agent half | Archived in git history, then removed from the working tree. 2,736 lines, 38% of main source, and the only reason `verifyPlugin` is red. |
| Asking Claude for review / tests / advice | Claude Code in the IntelliJ terminal, plus a `CLAUDE.md` that names where the current step lives. Zero plugin code. |
| First artifact | `git init` and a push. There is no repository today. |

Total tool-side budget: **one evening plus one afternoon.** After that the only
thing on the list is building the ledger.

---

## 1. Why a separate `verify/` project, and not tests in the service

You asked which design is more reliable. This one, and the reason is structural
rather than a matter of care.

A test that lives in `account-service/src/test` and imports
`io.ledgerflow.account.application.AccountRepository` is welded to your design.
Rename that interface, drop it, re-layer the service — and `mvn test-compile`
fails for the whole module. Not one red test: **no tests at all**, including the
three you wrote yourself. That is precisely the failure you described as "breaks
because it hasn't been implemented yet", and no amount of Maven configuration
removes it, because it is a property of the Java compiler.

`verify/` has no `<parent>`, appears in no aggregator's `<modules>`, and imports
nothing under `io.ledgerflow` except its own helpers. `mvn -f verify/pom.xml
test-compile` is green on an empty disk and stays green forever. It talks HTTP,
JDBC and a Kafka consumer to a running system. You can rename every package,
swap frameworks, delete a service and rewrite it — the spec still compiles and
still asks the same question.

That is also what makes it survive the thing you actually want: **learning many
topics on the same project over years.** In-module tests churn every time you
re-layer. A black-box spec does not. Adding Kafka Streams in eight months costs
one new file in `verify/src/test/java/` and nothing else moves.

Three judges scored this against black-box-in-reactor and shipped-JUnit-with-
seams. Unanimous. The seams design lost on a specific point worth remembering:
shipping you an interface to implement *makes the design decision for you*,
which is the thing you asked to stop.

### Layout

```
ledgerflow/
  verify/
    pom.xml                    no <parent>. junit-jupiter, assertj, postgresql,
                               kafka-clients, jackson-databind — all pinned.
                               maven.compiler.release=21 under your Java 25 JDK.
    mvnw  mvnw.cmd  .mvn/      its own wrapper (the repo root has none)
    lf.properties              every port, path, topic and container name
    src/test/java/io/ledgerflow/verify/
      Http.java  Db.java  Await.java        ~80 lines total, to start
      Preflight.java                        added when a spec needs it
      Topic.java  Docker.java               added when a spec needs them
      Step05Spec.java                       the first one
```

### The command

```bash
cd verify && ./mvnw -q -Dlf.spec=Step05Spec test
```

`<test>${lf.spec}</test>` in the pom, plus
`<failIfNoSpecifiedTests>false</failIfNoSpecifiedTests>` so a step with no spec
yet exits 0 instead of failing the build. That is the entire gating mechanism.
Surefire already does this; nothing is written to make it happen.

### Four properties that make it trustworthy

1. **Three outcomes, not two.** `Assumptions.abort()` in `@BeforeAll` when
   Postgres is down, the service is not up, or the topic does not exist →
   **amber, skipped**, one line: *"This is your machine, not your code."* Red is
   reserved for one meaning only. This is the single most important property
   here: it is what stops a tired engineer at 9:40pm mistaking harness noise for
   their own bug and concluding the whole thing is broken.
2. **Fresh data per test.** Every write mints a new UUID and wallet code and
   deletes it in `finally`. Nothing ever mutates the `V2__seed.sql` row. A
   killed JVM cannot poison the next run.
3. **One file owns every noun.** `lf.properties` holds ports, paths, topic
   names, database names, container names — all `-D` overridable. The repo
   already contradicts itself here: `infra/compose/docker-compose.yml` and
   `application.yml` both use **5433**, while `steps/02-give-real-database.json`
   ships `5432:5432`. Naming disagreements become one edit, not ninety.
4. **Red before it ships.** Every spec method is observed failing against the
   step's starting state before it is committed. A vacuously green test is worse
   than no test. Not machine-checkable; it is a rule.

Plus one line in `verify/src/test/resources/junit-platform.properties`:
`junit.jupiter.execution.timeout.testable.method.default=60s`, so a spec pointed
at a wedged service fails instead of hanging.

---

## 2. What is deliberately not built

Every item here was proposed, costed, and cut. Recorded so nobody rebuilds them.

- **No `@Step` annotation, no `Reach` ExecutionCondition, no extension
  autodetection, no `META-INF/services` file, no `-Dlf.upto`.** Surefire's
  `-Dtest` is the gate. (Related landmine, recorded in case a step number ever
  reaches Java: `Integer.getInteger("09")` throws — `decode` reads the leading
  zero as octal. Use `parseInt`.)
- **No dock Verify button, and therefore no `spec` schema field, no `Verify.kt`,
  no surefire-XML parser, no results strip, no `Ide.kt` fixes.** All of it
  exists to serve a button. The button is the most enjoyable item on the list,
  which is why it must wait. Add it in month two if the terminal command has
  been used fifty times. *(For the record: the Verify button has never worked
  on Windows. `Ide.kt:565` builds `cmd /c ./mvnw …`, which is not a valid
  command, while `runInTerminal` uses `bash -lc`. Every Maven-shaped check in
  the curriculum is silently un-runnable through it today.)*
- **No `Ticket` / `Ac` / `Spike` / `Debrief` data classes.** The valuable
  artefact is 400 words of a product owner's problem statement, and it is 400
  words whether it sits in eight typed fields or in the `goal` string that
  already renders.
- **No new block kinds, no hint ladders, no gated reveals, no `attempted`
  flag.** `Blocks.kt:50` already folds solution blocks behind *"Show the
  solution file (N lines)"*. The excess is in the content, and the fix is
  deletion.
- **No `Step01Spec` / `Step02Spec` / `Step03Spec`.** Those steps are done —
  `progress.json` records 01 and 02 complete, 03 at 4/6. Retrofitting specs onto
  finished work is two evenings proving something from three weeks ago.
- **No Testcontainers in `verify/`.** It is a client of the stack you already
  have running. A 40-second cold container start on the button you press most is
  the interruption you complained about.
- **No rewrite of steps 05–21.** You are on 03.

---

## 3. Content: what changes, and what does not

### The rule that decides — how many times have you met this pattern?

Not "hand over vendor facts, withhold reasoning". That rule sorts by provenance,
and provenance has nothing to do with difficulty. It would strip the worked
example from your *first* encounter with an outbox — the one place an example is
not optional — while leaving the *second* encounter handed over as a five-line
paste. That is backwards, and it has already cost something real: steps 13 and
20 hand you a publish-after-write that reintroduces the exact dual write step 10
spends three days forbidding. Nobody noticed, because nobody had to write it.

- **First encounter:** complete worked example, unfolded, with one sentence
  before it — *"before you open this, write down the three things this does and
  why that order."* Predict, then read. Step 07 already does exactly this and it
  is the best-shaped task in the corpus; copy its shape.
- **Second encounter:** the same block with **one decision blanked**, and a test
  on that decision. In step 13, ship the listener with the `@Transactional`
  boundary and the `markProcessed` call missing. In step 20, ship the apply with
  the send line missing and let the spec catch the dual write.
- **Third encounter:** test only.

This is *cheaper* than the alternative — you delete lines from blocks that
already exist instead of authoring a skeleton, three hints and a reference per
task.

### Behaviour steps versus structure steps

A black-box spec asserts behaviour. Roughly eight of the twenty-one steps teach
**structure** — layering, purity, boundaries — and no external assertion can see
those. Step 12's own text says *"keep this function pure… so you can unit test
every path in milliseconds"*, and a 300-line try/catch in a controller would
pass any behavioural acceptance criterion you could write for it. Green would
certify the opposite of the lesson.

So each step is one of two kinds, and the kind decides everything:

| | behaviour steps (06, 07, 09, 10, 11, 13, 18, 19, 20, 21) | structure steps (01–05, 12's transition fn, 16, 17) |
|---|---|---|
| the spec | is the specification — build against it | cannot be the specification |
| solution blocks | delete the ones the spec asserts | **keep in full** — the example *is* the spec |
| the proof | red → green | reading the reference against your own code |
| the dock says | the spec name | *"No spec here. Step 03 is about shape, and nothing observed from outside can tell a well-layered service from a working one."* |

That last sentence matters. An empty acceptance panel reads as *"this step does
not matter"*, which is the opposite of true.

### The noun rule

A spec that runs `select count(*) from outbox where published_at is null`
against a ticket carefully written so the word *outbox* never appears is a
guessing game with extra steps. Real tickets hand over the integration contract
and withhold the mechanism.

**Every proper noun an assertion mentions — table, topic, endpoint path — must
appear verbatim in the step's ticket.** Withhold *how the two writes become
one*. Do not withhold what the table is called.

### Kept, untouched

Every `why`, every `newIdeas` entry, every `proof.note`. The lesson engine and
its 21 audited triggers. The per-step JSON format and `stepId:key` ticks — do
not re-cut task keys. The existing solution fold at `Blocks.kt:50`. The Session
state machine. The Map. And `account-service` exactly as written, including the
`JdbcClient` repository you wrote instead of the JPA adapter step 02 handed you
— that divergence is the curriculum working in spite of itself.

### One step ahead, hard rule

Author step N+1 on the evening step N finishes. Ticket prose into `goal`, spec
written, solution blocks deleted where the spec covers them. It self-enforces:
you cannot honestly write an assertion about a topic you have not yet published.
Do not touch 05–21 speculatively — authoring 21 steps of finished solution code
before running any of them is what produced content where step 05's `PlaceHold`
and step 12's `PaymentSaga` do not compile.

---

## 4. The plugin

You asked whether it can be made fully reliable. Yes — by subtraction.

`ChatController` + `AgentLoop` + `AgentTools` + `Reviewers` + `ChatDb` +
`TrainerApi` + `AddSelectionToChatAction` + `ui/Chat` + `ui/ReviewerConfigurable`
= **2,736 lines, 38% of main source**, rendering no curriculum. `Ide.problems`
goes with them, and that is the sole `INTERNAL_API_USAGES` finding making
`./gradlew verifyPlugin` red. Remove the half, and `verifyPlugin` exits 0 as a
side effect rather than as a project.

Also drop the hardcoded curriculum sizes at `ProgressCompatibilityTest.kt:31-33`
(`assertEquals(21, …)`, `assertEquals(106, …)`) — editing content should not
turn the test suite red for a non-defect.

### Archived, not deleted — and the order matters

Git history *is* the archive, so the sequence is:

1. **Commit `ide-trainer/` whole in the first commit.** That commit is the
   archive. Do not `.gitignore` it — a gitignored folder cannot be archived.
2. **Second commit removes the chat half.** Tag it `chat-agent-v1`.
3. Recovery is permanent and costs nothing:
   `git show chat-agent-v1:ide-trainer/src/main/kotlin/io/tanvoid0/codecraft/ChatController.kt`

The rest of the plugin stays tracked. At ~4,400 lines of tested Kotlin against a
real IntelliJ Platform API, it is a good portfolio artefact in its own right;
the liability was only ever the unfinished agent inside it.

**The freeze is structural, not physical: the daily loop contains no plugin
command.** That is already true, because the loop is
`cd verify && ./mvnw -Dlf.spec=… test`. Do not move the plugin to its own repo —
an evening of gradle path fixes with nothing visible at the end will be
deferred, and then the freeze will not hold.

### Why the chat half is not worth finishing

ADR-002 records what it actually is: two backends (a local Ollama tool loop
capped at 8 rounds, and a server-side Coder), tools `read_file` / `list_dir` /
`search` / `run_command`, and **no edit tool, by design**. It can diagnose and
never fix. The edit tool was proposed on 2026-08-29 and never built, and the
server backend cannot be permission-gated from inside the plugin at all.

So it is not a weak assistant — it is an unfinished one, on a local model that
cannot hold a saga, an outbox and three services in view at once. A larger local
model does not fix that; not running a model in the plugin does.

### The backdoor: Claude Code in the IDE terminal, zero plugin code

Review, testing and recommendations already work today — Claude Code runs in
IntelliJ's terminal with the repo as its working directory, and reads files,
runs commands and reviews diffs. The official JetBrains plugin adds a diff view
if wanted. Nothing needs building.

The only thing the trainer knows that a bare terminal does not is *which step is
open and what it must satisfy*. That is a text file, not a feature. Put a
`CLAUDE.md` at the repo root:

```
Current step:  ide-trainer/experiments/ledgerflow/progress.json → summary.currentStep
Its contract:  ide-trainer/experiments/ledgerflow/steps/NN-*.json  (goal, doneWhen, why)
Its spec:      verify/src/test/java/io/ledgerflow/verify/StepNNSpec.java
Verify:        cd verify && ./mvnw -q -Dlf.spec=StepNNSpec test
```

Then "review what I just wrote" self-locates, every session, with nothing to
keep in sync.

**If — and only if — typing `claude` becomes annoying after fifty uses**, the
button is ~15 lines on methods that already exist and are already tested: write
the step context to `.claude/ask.md`, then
`Ide.runInTerminal("claude \"read .claude/ask.md and do what it says\"")`. The
command string is fixed and the variable content lives in the file, so there is
no shell-quoting hole — and `runInTerminal` (`Ide.kt:310`) uses `bash -lc` and
works today, unlike `runCapturing`. Do not build it in advance.

---

## 5. Repo hygiene — evening one, one hour

There is no git repository. `git rev-parse` returns fatal. The stated goal is a
demoable GitHub project.

- `git init`, `.gitignore` covering `target/ build/ .gradle/ .idea/ *.db
  ide-trainer/`
- `rm -rf adapter/ application/ infra/compose/test` — step 02's blocks written
  to the wrong folder. `adapter/out/persistence/AccountRepositoryAdapter.java`
  has had a stray `6` on line 1 since 29 Aug; nothing compiles it.
- Drop `spring-boot-starter-data-jpa` from `account-service/pom.xml:44` — unused;
  the service uses `JdbcClient`.
- Rename `AccountRepositoryIT` → `AccountRepositoryTest`. There is no failsafe
  plugin in the pom and `*IT` matches none of surefire's default includes, so
  the one integration test in the repo **has never run**.
- **Rewrite `README.md` down to about ten true lines.** It currently opens
  *"Work through it with `ledgerflow.html`"* — that file is deleted. It
  advertises milestone A as delivering double-entry postings, an idempotency key
  and a concurrency test, and names `AuthorizeConcurrencyTest`,
  `LedgerInvariantTest` and `OutboxCrashTest` under *"Proved by"*. Those three
  strings exist only inside `steps/17-*.json`. You have ticked milestone A
  complete and it delivered none of its three advertised properties. Add a row
  back only when a named spec is green.
- `rm` the debris: ten 15MB zips in `build/distributions`, `chats.db`, the four
  bare experiment paths (regenerated output, zero ticks), `NATIVE-UI-PLAN.md`
  and `ide-integration-plan.md` — both describe a JCEF board that no longer
  exists.
- Commit. Push.
- Last thing while still at the keyboard: `cd verify && ./mvnw -q
  dependency:go-offline`, so the 40-second cold download is paid deliberately
  and not mid-step at 9pm.

---

## 6. Sequence

Work-day evenings ~1h; occasional full days off.

| when | do | proves it |
|---|---|---|
| E1 | git init, **commit everything including `ide-trainer/`**, debris, README rewrite, `CLAUDE.md`, `AccountRepositoryTest` rename, push | repo is public, README is true, and the archive commit exists |
| E2 | `verify/` pom + wrapper + `Http`/`Db`/`Await` (~80 lines) + **`Step05Spec`** | it is **red** — ledger-service does not exist |
| E3 | finish step 03 (RequestIdFilter, OpenAPI) | existing checks pass |
| Day off | step 04 — the Maven reactor. No ticket, no spec: its `doneWhen` is build topology | `./mvnw -T 1C clean install` lists three modules |
| E4–E6 | step 05 — make `Step05Spec` go green | first real red→green, on work that did not exist on Monday |
| Sat | remove the plugin's chat half, tag `chat-agent-v1`; `verifyPlugin` green; close it | `./gradlew test` + `verifyPlugin` both green, and `git show chat-agent-v1:…` still returns the file |
| E7 | nine-line GitHub Actions: `mvnw -f verify/pom.xml -Dlf.spec=Step05Spec test` + `mvnw -pl account-service test` | green badge on the README |
| E8+ | step 06 onward, one step ahead: author N+1 the evening N finishes | one spec goes red, then green, per step |

The first red→green lands on **evening 2**, not evening 6. That ordering is the
whole point: no plumbing stretch, no specs for finished work, no unrewarded
week. By end of week two the README carries one named property proved by one
green spec and a CI badge. One true row beats four aspirational milestones.

---

## 7. Decisions still yours

1. ~~Delete the plugin's chat/agent half?~~ **Settled 2026-09-06:** archived in
   the first commit, removed in the second, tagged `chat-agent-v1`. Backdoor is
   Claude Code in the IDE terminal plus `CLAUDE.md`. See §4.
2. **Write the specs yourself, or generate them in a separate session?** —
   Recommend generate, four steps at a time, from the step's `doneWhen`, then
   read only the failure output. A ticket you wrote yourself at 9pm is your own
   plan with a JUnit wrapper; the withholding is theatre otherwise.
3. **The four bare experiment paths (`dsa-interview`, `java-professional`,
   `spring-professional`, `career-plan`) — delete or keep?** — Recommend delete
   from this repo. They are regenerated output of `tools/gen-experiments.js`
   from a tracked repo elsewhere, and they have zero ticks.
4. **`D:/projects/practice/microservices/kafka/PLAN.md`** — delete it. Kafka is
   steps 07–12 and 18–21 here. Two repos teaching the same thing is the
   distraction this document exists to remove.
