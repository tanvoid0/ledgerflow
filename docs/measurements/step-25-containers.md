# Step 25 — the same eight jars, now in containers

## No Dockerfile

The root pom's `pluginManagement` configures `spring-boot-maven-plugin`
once - image name `ledgerflow/<artifactId>:<version>`,
`BP_JVM_VERSION=25` - and every service module inherits it just by having
a main class; `scripts/build-images.sh` runs `spring-boot:build-image` on
the eight service modules (a lib has no main class, so it's the eight,
not all eleven). Paketo picks the JDK, splits the jar into layers by
change frequency - dependencies, snapshot dependencies, application - so
a rebuild after touching one class ships one layer, not the jar.

A hand-written Dockerfile doing the same layering is thirty lines you own
forever: which base image, which JDK, `COPY` order for cache hits, a
non-root user, a healthcheck. Write one only when something forces it -
an air-gapped registry, a mandated base image. The layered jar is still
there without a container at all:
`java -Djarmode=tools -jar app.jar extract --layers`.

Eight image sizes and the total build time, buildpack-picked JDK:

```
account-service         989MB  (796.8MB shared base + 191.7MB unique)
balance-service         949MB  (796.8MB shared base + 151.9MB unique)
issuer-service          915MB  (853MB shared base   +  62.3MB unique)
ledger-service          989MB  (796.8MB shared base + 192.2MB unique)
notification-service    915MB  (853MB shared base   +  62.3MB unique)
payment-service         931MB  (796.8MB shared base + 134MB unique)
risk-service           1.22GB  (796.8MB shared base + 422.5MB unique - Ollama/narrator deps)
settlement-service      923MB  (796.8MB shared base + 125.7MB unique)
JDK: BellSoft Liberica JRE 25.0.4 (openjdk 25.0.4 2026-07-21 LTS), buildpack
paketo-buildpacks/bellsoft-liberica, confirmed from inside a running container:
java -version -> OpenJDK Runtime Environment (build 25.0.4+9-LTS)
build-images.sh (8 images, mvn install first, warm buildpack/pack-cache): well under 6
minutes end to end; a single service built standalone cold (builder+run image pull
included) took 45.4s.
```

## Every address is configuration

Every literal host in every service yml became `${ENV:host-default}` -
`DB_URL`, `DB_USER`, `DB_PASSWORD`, `KAFKA_BOOTSTRAP`, `OTLP_ENDPOINT`,
`REDIS_HOST`, `OLLAMA_URL`, `ACCOUNT_BASE_URL`, `ISSUER_BASE_URL`,
`SERVER_PORT`. The defaults keep the host workflow alive - `./mvnw
spring-boot:run`, `scripts/demo.sh` need nothing new - and the compose
file supplies the container values. `ledgerflow.issuer-base-url` reads
from `ISSUER_BASE_URL` through Boot's relaxed binding, no `@Value` or
mapping written for it.

Inside a container, `localhost` means the container, not the host or a
neighbor: a service that hardcodes it starts clean, passes its own health
check, and only fails on the first request that needs Postgres, Kafka, or
another service - the most expensive shape of broken, because everything
green up to that point says the opposite.

`server.shutdown: graceful` plus `spring.lifecycle.timeout-per-shutdown-phase:
30s` on every service means a container told to stop finishes the request
it is mid-way through rather than dropping it; step 26 stops pods
constantly, and this is the setting that makes that survivable.

## One port inside, eight outside

Every container listens on 8080 (`SERVER_PORT=8080`); the compose file
maps the same host ports every service has held since step 01 (8080-8087)
onto that one internal port. One image configuration, one health check
shape, one Kubernetes manifest shape - and every curl from steps 01
through 24 still works unchanged against the same host ports.

`depends_on: service_healthy` on Postgres and Redpanda delays a
service's own startup until its own dependencies answer, but it says
nothing about ledger needing account to be *useful*, only *up* - steps 06
and 09 (timeout, retry, degrade) are still the real answer to that.
Inside the compose network Kafka is `redpanda:29092`, the PLAINTEXT
listener; 9092 is the OUTSIDE listener advertised as `localhost`, and a
service inside the network that dials `redpanda:9092` connects fine and
then times out on every send, because the broker hands back a bootstrap
metadata response pointing at `localhost:9092` from the container's own
point of view.

`group.instance.id` is `<service>-${server.port}`, which resolves to
`<service>-8080` in every container - correct with one replica per
service, and the reason two replicas of the same service would fence
each other on the same static group instance id if they existed today.
The fix is naming the id after the pod, not the port - step 26.

## Same image, two shapes

`docker compose run --rm -e SPRING_BATCH_JOB_ENABLED=true -e
SPRING_BATCH_JOB_NAME=agedItemSweepJob -e
SPRING_MAIN_WEB_APPLICATION_TYPE=none settlement runAt=...` runs the
job and exits; the process exit code is the entire contract a scheduler
needs - 0 for COMPLETED, non-zero for anything else. Step 26's CronJob
supervises that exit code, not a `@Scheduled` method living inside a
replicated web service that happens to also run batch jobs.

Two things had to be true for that sentence, and neither was on the first
run. `JobLauncherApplicationRunner` only rethrows when the job fails to
*launch* (bad parameters, an already-complete instance); once launched it
logs COMPLETED or FAILED and returns, and `main` returned to a JVM the
Kafka listener threads kept alive forever - `web-application-type=none`
stops Tomcat, not the listeners. A clean run and a failed run both hung
until `docker stop` (exit 143, a signal, not a verdict). The fix is four
lines in `main`: when `spring.batch.job.enabled` is set,
`System.exit(SpringApplication.exit(ctx))` - Boot's
`JobExecutionExitCodeGenerator` is already a bean and maps the
`BatchStatus` to the code. And `--businessDate=2026-09-03` is a Spring
*property*, not a job parameter: `JobParametersConverter` reads only
bare, non-`--` arguments, so the `--` form launched the job with `[{}]`
and failed on a null date. `businessDate=2026-09-03` binds.

Three runs and their exit codes - a normal sweep, a repeat of an
already-completed date, and a run built to fail:

```
agedItemSweepJob runAt=<epoch>                            COMPLETED in 47ms          exit 0
nightlySettlementJob businessDate=2026-09-03 (already ran) JobInstanceAlreadyComplete  exit 1
                                                           thrown before launch
nightlySettlementJob businessDate=2026-09-17, 10 NEW items,
  issuer fx armed for 10 faults                           FAILED in 3.3s (4 retries,  exit 5
                                                           200/400/800/1600ms backoff
                                                           exhausted); all 10 still NEW
```

5 is `BatchStatus.FAILED.ordinal()` - the generator returns the ordinal,
which is why "non-zero" is the contract and not any particular number.

## The heap follows the cgroup

A modern JVM reads the container's memory limit, not the host's, and
sizes the heap off it; `MaxRAMPercentage=70` leaves room for metaspace,
thread stacks, and direct buffers outside the heap. At 90% the kernel
OOM-kills the container with no stack trace anywhere - Java never gets a
chance to log an `OutOfMemoryError` because the process is gone, not the
heap. Compose sets `mem_limit: 768m` per service; 70% is a starting
point, not a proof, and the checkpoint below is what says whether 768m
holds under load, not this section.

`MaxHeapSize` as JVM actually resolves it, once running inside the
768m container and once under `docker run -m 256m`:

```
running container, 768m mem_limit, JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=70 (compose env):
  MaxHeapSize = 564133888  (~538MiB, exactly 70.0% of 768MiB)

docker run -m 256m, no compose env (MaxRAMPercentage isn't baked into the image):
  MaxHeapSize = 132120576  (~126MiB, ~49% of 256MiB - JVM's own small-container
                            ergonomics substitute a MinRAMPercentage-style rule below
                            the ~256MiB range instead of the flat 25% default)

docker run -m 1g, no compose env, for contrast:
  MaxHeapSize = 268435456  (=256MiB exactly, 25% of 1024MiB - the textbook default)
```
Every number is cgroup-limit-based, not host-based, in all three cases - only which
percentage-of-limit rule applies changes with `JAVA_TOOL_OPTIONS` and container size.

## Start faster

Java 25's AOT cache (CDS's successor, and what Boot 4.1 recommends from
24 on) records the classes a training run loaded, linked, and verified,
plus - new on 25 - some heap objects created during class init. Two
preconditions: the jar has to be extracted first, because the cache is
tied to a stable classpath, and the training run stops early at
`spring.context.exit=onRefresh` rather than actually serving traffic.

What the AOT cache does not touch: anything Spring decides at runtime -
bean definitions, `@Conditional` evaluation, proxies. Boot's own AOT
processing freezes those too, for a bigger startup win, at the cost of
baking `@Profile` and `@ConditionalOnProperty` decisions into the build -
the same jar can no longer change shape from an env var afterward, which
undoes the point of the config-by-environment work earlier in this step.
Not adopted here for that reason.

Plain start vs. AOT-cached start, three runs each, median:

```
settlement-service, extracted jar, host JDK 25 (Temurin 25.0.4), one-step AOTCacheOutput:
run 1: plain 2.608s  aot-cache 1.154s
run 2: plain 2.599s  aot-cache 1.048s
run 3: plain 2.585s  aot-cache 1.095s
median: plain 2.599s  aot-cache 1.095s  -  the AOT cache more than halves startup (~58%
faster) for a service this size, consistent with skipping class loading/linking/verification
for a classpath that was already stable at training time.
```

## From nothing

`scripts/demo-compose.sh` runs `down -v` (drop volumes, because Redpanda
has none it can resume from anyway), builds the eight images, `up -d
--wait` (blocks on every health check passing - no sleep-and-hope), then
registers the Kafka topics and schemas Redpanda otherwise starts without,
runs two assertions, and drives the same three scenarios `demo.sh` does
on the host. `demo.sh` itself is unchanged and still runs against host
JVMs, because the perf work in this curriculum runs on the host, not in
Docker Desktop's VM.

Wall time for the whole script, cold:

```
start 12:27:37Z, "the whole system is up" 12:36:23Z -> 8m46s cold: down -v, eight
buildpack builds (warm buildpack cache, a cold pull of the builder is on top of that),
up -d --wait, topics + schemas, the three scenarios.
docker compose ps: 12 of 12 healthy - the four infra containers and the eight services.
```

The first run did not get there. `up --wait` stopped on "container
ledgerflow-balance-1 is unhealthy" while every service answered 200 from
the host: the healthcheck's `printf` was written as a YAML double-quoted
string with real line breaks in it, which YAML folds to a single `
` -
the HTTP/1.0 request never got its blank line, the server waited for
more, and the check timed out at 3s every time. `

` escaped in
the YAML is the fix; a healthcheck that speaks HTTP by hand has to
speak it exactly. The 11m27s of that run was the same work minus the
scenarios, on a colder cache.

## Checkpoint

`perf/bench.sh step25` against the containerised stack - step 15's load
shape again, this time with every service, Postgres, Redpanda, and Redis
sharing the one Docker Desktop VM (WSL2) instead of running on the host
directly. Run twice, because the first run said two things at once:

```
                    transfer 100/s 60s        holds 50/s 30s       payments 100/s 180s, settled
step24 host JVMs    p99 8ms    failed 0%      p99 8ms              p50 908ms  p99 24332ms  18005/0
step25 run 1        p99 2324ms failed 0.06%   p99 2417ms           p50 334ms  p99 1484ms   18004/0
step25 run 2        p99 9ms    "failed" 4.31% p99 78ms             p50 316ms  p99 3351ms   18005/0
```

Run 1 was the first traffic the containers had ever seen: `up --wait`
and straight into k6. The 2.3s tails on transfer and holds are the C2
compiler and Hibernate warming eight fresh JVMs at once inside one VM,
not a container tax - run 2, on the same containers after `demo-compose.sh`'s
scenarios had already exercised them, puts both back where the host had
them. Payments, which run third in the suite, never saw the cold JVMs in
either run. Warm-up is real cost the host runs had amortised over hours;
a scheduler that starts a pod and immediately routes it 100/s pays it.
Step 27's readiness probe is where that goes.

Run 2's "4.31% failed" is k6's `http_req_failed` counting 422s: every
response was 201 or 422 (the script's own check passed 6302 of 6302).
`down -v` had wiped the wallets back to their seed balances, and one
minute of random 1p transfers empties the small ones; on the host the
same wallets carried months of top-ups. A refusal is the ledger working.

Settled p99 in containers - 1.5s and 3.4s across the two runs against the
host's 24.3s - is the number that moved most, and in the direction the
prose above did not predict. `docker stats` mid-payments in run 2:

```
ledger-1      136% CPU  417MiB / 768MiB      postgres  141% CPU
settlement-1  115% CPU  369MiB / 768MiB      redpanda   35% CPU  2.0GiB
payment-1      89% CPU  404MiB / 768MiB      lgtm       41% CPU  1.1GiB
account-1      47% CPU  386MiB / 768MiB      the other four 25-54% CPU, 310-383MiB
```

Nothing is near its 768m limit, so memory is not it. Postgres and
Redpanda ran in the same containers, on the same named volume, for the
host runs too - what changed is the path to them. A host JVM reaches
`localhost:5433` through Docker Desktop's port forwarding into the WSL2
VM, one proxy hop per connection, per query, per Kafka fetch; a
container on the compose network reaches `postgres:5432` directly. With
eight services each holding a pool and a consumer, that hop is the thing
step 15's tail was sitting on. A hypothesis this checkpoint cannot
separate from CPU scheduling inside one VM - noted, not proven. Either
way: 768m holds under load, all eight.

## Kept

- The pom's shared `spring-boot-maven-plugin` configuration and
  `scripts/build-images.sh`.
- Every `${ENV:default}` override, and the compose file that supplies
  the container-side values.
- `scripts/demo-compose.sh` and `scripts/startup-time.sh`.

Not kept:

- Boot AOT processing - it buys startup time by giving up the
  env-driven shape this step just built.
- A Dockerfile - the buildpack does the layering without one, and
  nothing here forced a custom base image yet.
