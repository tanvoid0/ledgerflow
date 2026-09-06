# Skill gaps

Moved here from the portfolio repo on 2026-08-28, because this project is
where the gaps get closed. The **The plan** tab in `ledgerflow.html` sequences the work; this file
holds the market evidence for why each item is on it. The re-run command below
still points at the portfolio's dev server, which is where the job inbox lives.

What is missing from the record, ranked by how often the market actually asks
for it, plus what "knowing" each thing means past the commands you already
type every day.

**Where the numbers come from.** Every percentage below is a count over 125
real adverts: the 102 rows in the job inbox plus the 23 in
`corpus/real-unlabelled.jsonl`. All 125 have a full body, median about 5,000
characters, so these are whole adverts and not snippets. Re-run the count when
the inbox has moved, against a dev server and a `content:read` agent token:

```bash
curl -s "http://localhost:4321/api/admin/jobs?limit=500" -H "Authorization: Bearer $PORTFOLIO_TOKEN" | node -e 'let d="";process.stdin.on("data",c=>d+=c).on("end",()=>{const a=JSON.parse(d).jobs.map(x=>(x.title||"")+" "+(x.description||x.snippet||""));for(const p of [/\baws\b/i,/\bgcp\b|google cloud/i,/terraform|pulumi|cloudformation/i,/kafka/i,/observabilit|prometheus|grafana|datadog|opentelemetry/i,/\bmaven\b|\bgradle\b/i,/graphql/i])console.log(String(a.filter(s=>p.test(s)).length).padStart(4)+" / "+a.length+"  "+p)})'
```

Single-quote that program. Double quotes let the shell eat the `\b` word
boundaries, which silently reports zero for every pattern that uses one.

**How to use this.** Tier 1 costs you applications today. Tier 2 costs you
interviews once you are in the room. Tier 3 is a list of things that look like
gaps and are not, kept so nobody spends a weekend on them twice.

---

## Tier 1: keyword gaps, costing applications now

Each of these is absent from the record and common in the adverts. An ATS
filtering on the term drops you before a person reads anything.

### AWS — 64 of 125 adverts (51%)

33 adverts name AWS and never mention GCP, so a quarter of the market cannot
see your cloud experience at all.

This is a naming gap more than a skill gap. Kubernetes, Docker, managed
Postgres, a message broker and a CI pipeline are the same job on either cloud.

- [ ] Map what you already run to the AWS names: GKE to EKS, Cloud Run to ECS
      Fargate, Pub/Sub to SNS plus SQS, Cloud SQL to RDS, GCS to S3, Cloud
      Build to CodeBuild, Secret Manager to Secrets Manager.
- [ ] Deploy one thing you already have to AWS so the claim is true. Portal
      Desktop's release pipeline or agent-platform in ECS both work.
- [ ] Learn the parts with no GCP equivalent worth guessing: IAM roles versus
      resource policies, VPC and security groups, and why an ALB is not an
      Ingress.
- [ ] Consider AWS Solutions Architect Associate. It closes this and the empty
      certifications section in one move.

### Observability tooling — 50 of 125 (40%)

The largest gap by surprise. The record has one Sentry mention and a
"post-deploy monitoring" practice, and names no tool. Adverts name Prometheus,
Grafana, Datadog and OpenTelemetry.

This one has an unusually good closing move, because agent-platform is a
gateway and a gateway is the natural place to measure things. Per-provider
latency, token spend, error rate and fallback frequency are metrics the system
should have had anyway, and they land directly on top of "prove what they cost".

- [ ] Instrument agent-platform with OpenTelemetry: traces across the request,
      one span per upstream call.
- [ ] Prometheus and Grafana in the same Docker Compose file, with a dashboard
      of cost per provider.
- [ ] Learn the vocabulary properly: the difference between a metric, a log and
      a span; what cardinality is and why a label per user destroys a metrics
      store; RED versus USE; what an SLO and an error budget actually commit you
      to.

### Infrastructure as code — 32 of 125 (26%)

Absent entirely. Jenkins and Docker are in the record; nothing declares
infrastructure.

- [ ] Express one existing deployment as Terraform. The AWS target above, or
      the Vercel and MongoDB Atlas setup behind portfolio-core.
- [ ] Understand state: what the state file is, why it is the dangerous part,
      why remote state with locking exists, and what `terraform import` is for.
- [ ] Know plan versus apply, and why a plan that shows a replace instead of an
      update is the moment to stop.

### Kafka — 29 of 125 (23%)

You have RabbitMQ, which is real event-driven experience. The gap is the model,
not the API, and interviewers ask about the model.

- [ ] The core distinction: Kafka is a replayable log, RabbitMQ is a queue that
      forgets. Consumers own an offset; nothing is removed when it is read.
- [ ] Partitions are the unit of both ordering and parallelism. Order holds
      inside a partition and nowhere else, so the partition key is a design
      decision.
- [ ] Consumer groups, rebalancing, and what a rebalance storm looks like.
- [ ] At-least-once plus idempotent consumers, and where exactly-once really
      applies.
- [ ] Log compaction, and why it makes a topic a table.
- [ ] Redpanda in Docker Compose is the cheapest way to have actually run one.

### Azure — 21 of 125 (17%) and Go — 39 of 125 (31%)

Lower priority, listed so the decision is deliberate rather than forgotten.
Go appears often but usually in shops that will train a strong Java engineer
into it. Azure is worth it only for a specific employer you want.

- [ ] Decide once whether to chase Go, and if not, stop reading Go-only adverts.

---

## Tier 2: depth gaps in things you use every day

These do not cost you the application. They cost you the interview, because
"I use it daily" and "I can explain what it does" are different answers.

### Maven

You use it every day. Here is what sits above the command line, roughly in the
order an interviewer would reach for it.

- [ ] **Dependency mediation is nearest-wins, not newest-wins.** Two paths to
      the same artifact resolve to whichever is fewer hops from your pom,
      whatever the versions are. This is the single most common cause of an old
      library appearing on the classpath.
      `mvn dependency:tree -Dverbose -Dincludes=group:artifact` prints the
      losers as "omitted for conflict".
- [ ] **`dependencyManagement` versus `dependencies`.** The first declares a
      version without putting anything on the classpath; the second adds it. A
      BOM import (`<type>pom</type><scope>import</scope>`) is how Spring Boot
      pins hundreds of versions consistently. Knowing this is the line between
      editing a pom and owning the build.
- [ ] **Surefire versus Failsafe.** Surefire runs `*Test` at the `test` phase
      and fails the build immediately. Failsafe runs `*IT` at
      `integration-test` and defers failure to `verify`, so
      `post-integration-test` still runs and tears down. This one is directly
      yours: a Testcontainers test under Surefire is why `mvn test` crawls and
      why a container is left running when a test dies.
- [ ] **Lifecycle: a phase is not a goal.** `install` implies `package` implies
      `test` implies `compile`. A plugin binds a goal to a phase. Being able to
      say which phase a plugin binds to is how you answer "why was the jar not
      repackaged".
- [ ] **CI should run `verify`, not `install`.** `install` writes into the
      shared, mutable `~/.m2`. Most "works on my machine" in a Maven shop is a
      stale local artifact.
- [ ] **Maven has no lockfile.** npm has one, Maven does not. Reproducibility
      comes from banning version ranges and pinning plugin versions, enforced
      by `maven-enforcer-plugin` with `requireReleaseDeps`,
      `banDynamicVersions` and `dependencyConvergence`. An unpinned plugin
      version means your build changes when Maven Central does.
- [ ] **Scope transitivity.** `compile` is transitive; `provided` and `test`
      are not. Getting it wrong is how a test library ships in a production jar.
- [ ] **The reactor.** Module build order is computed from inter-module
      dependencies, not from the order in `<modules>`. `-pl <module> -am`
      builds one module and only what it needs, which is a 12-minute loop
      turned into a 40-second one.
- [ ] **`spring-boot-maven-plugin repackage` is not shade.** Boot's fat jar is
      a nested-jar layout with a custom classloader, not a flattened uber-jar.
      That is why unpacking one and running `java -cp` does not work.
- [ ] **`settings.xml`, mirrors and CI auth.** Where private repository
      credentials live, why pipelines pass `-s`, and why a mirror of `*` breaks
      a build that needs one specific repository.

If you only take four into an interview: nearest-wins mediation, BOM imports,
Surefire versus Failsafe, and phases versus goals.

### GraphQL

Your daily work, and the record now says so. The depth questions are narrow and
predictable.

- [ ] **N+1 and DataLoader batching.** The defining GraphQL problem, and
      sharper in your case because the resolvers fan out to separate Spring Boot
      services. Per-request batching and caching is the answer; be able to
      describe it without the library name.
- [ ] **Query depth and complexity limits.** An unbounded GraphQL endpoint is a
      denial-of-service primitive: a client can ask for a cycle. Know that
      depth limiting, complexity scoring and persisted or allowlisted operations
      exist.
- [ ] **Know which pattern yours is:** federation, schema stitching, or a
      hand-written gateway, and why that choice was made.
- [ ] **Errors return HTTP 200 with an `errors` array.** Naive status-code
      monitoring reports a healthy gateway while every query fails.
- [ ] **Caching is genuinely harder than REST**, because everything is a POST to
      one URL. Persisted queries over GET is the usual way back to edge caching.

### Spring Boot and JPA

- [ ] **`@Transactional` is proxy-based**, so a self-invoked or private method
      is not transactional at all. Classic interview question, silent bug in
      production.
- [ ] **Transaction propagation**: what `REQUIRES_NEW` really does, and why it
      needs a second connection.
- [ ] **The dual-write problem.** You write to Postgres and publish to RabbitMQ
      in one method. If the publish succeeds and the transaction rolls back, or
      the reverse, the system is inconsistent. The transactional outbox pattern
      is the standard answer and it sits exactly on top of your payment work.
- [ ] **Name the mechanism behind the 99-second fix**, not just the outcome:
      which access pattern, which index, what the plan showed. You already did
      the work; the interview asks you to explain it.
- [ ] **Connection pool sizing.** Why raising the HikariCP pool usually reduces
      throughput, and how pool size relates to database cores.

### Kubernetes

Recorded as level 3, "daily on GCP to develop and test". The gap is operational.

- [ ] Requests versus limits, and the asymmetry: exceeding a CPU limit throttles,
      exceeding a memory limit kills. Being able to say why a pod was OOMKilled
      is the whole question.
- [ ] Readiness versus liveness versus startup probes, and how a liveness probe
      that is too aggressive turns load into a restart loop.
- [ ] What zero-downtime actually requires: a readiness probe, `maxSurge` and
      `maxUnavailable`, a `preStop` hook, and a grace period longer than your
      longest request.
- [ ] A Secret is base64, not encryption.
- [ ] Why `kubectl apply` is a declaration to a reconcile loop and not a command.

### PostgreSQL

- [ ] Read `EXPLAIN (ANALYZE, BUFFERS)` out loud: estimated versus actual rows,
      and what a large gap between them means.
- [ ] Partial and covering indexes, and when a btree is the wrong index.
- [ ] MVCC, dead tuples, bloat and autovacuum: why a table degrades with no
      schema change at all.
- [ ] Isolation levels, and what a serialization failure obliges the caller to do.

---

## Tier 3: checked, and not gaps

Kept so the same weekend is not spent twice.

| Thing | Adverts naming it | Verdict |
|---|---|---|
| Maven, Gradle | 0 and 1 of 125 | Never an ATS keyword. Depth matters for interviews only, which is why it is in Tier 2 and not Tier 1. |
| Jest, pytest, JUnit by name | 0 of 125 | Adverts ask for "testing", never for a runner. Do not optimise the CV for one. |
| RAG, vector databases, LangChain, vLLM | about 0 of 125 | The Applied AI adverts in this sample are agent-shaped and platform-shaped, not retrieval-shaped. agent-platform is already on target. Revisit only if the sample changes. |
| GraphQL | 3 of 125 | Your daily work is nearly invisible to keyword filters. Keep it in the record, never lead with it. |
| PHP | not counted, not asked | Left out of the record deliberately. |

---

## Standing questions

- **Certifications is empty.** AWS Solutions Architect Associate would close the
  largest Tier 1 gap and the empty section together. Anthropic's Claude
  certifications (launched 2026, via Anthropic Academy) are the second
  candidate: the Developer exam (~$125, Messages API, tool use, agents) names
  the exact work agent-platform already does, so it converts existing
  experience into an ATS-visible credential rather than demanding new study.
  Both live on the **The plan** tab's m3 rider — after applications flow, never
  before.
- **Most of the Civica work cannot be described in public.** The usual line is
  that shape is sayable and specifics are not: scale, architecture, technology
  and outcome, with no council named, no internal system name, no data and no
  screenshot. Check your own contract rather than this note. Anything that sits
  the wrong side of that line, but that a specific employer should still see,
  belongs behind the token-gated private record, which is released one recruiter
  at a time and revocable. That is what the access-link feature is for.
