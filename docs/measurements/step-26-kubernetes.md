# Step 26 — the cluster owns the timetable

## A cluster on the laptop

`kind` v0.33, one cluster named `ledgerflow`, one control plane and two
workers - a single node never shows a pod landing anywhere but where you
are standing, which is most of what makes Kubernetes different from
compose. `extraPortMappings` maps 8080-8087 on the control-plane container
to NodePorts 30080-30087, so every curl in the README, `scripts/scenario-*.sh`
and `perf/bench.sh` hits the cluster on the same host ports they have used
since step 01 - no port-forward sits in the measurement path. That mapping
is also why the host stack and the cluster cannot both hold those ports:
the host JVMs are down for this step, and `scripts/k8s-down.sh`
(`kind delete cluster --name ledgerflow`) is what gives them back.

The nodes cannot see the host's Docker daemon, so an image built by
`spring-boot:build-image` is invisible to them until it is pushed in by
hand: `kind load docker-image` once per service, eight times, with
`imagePullPolicy: IfNotPresent` on every Deployment so the kubelet never
goes looking for a registry that does not exist. Skip the load and the
first symptom is `ErrImagePull` sitting there forever, not an error you
read once and fix.

Eight services at two replicas is sixteen application pods, spread across
two workers by the scheduler's own judgment, not a rule this repo writes:

```
$ kubectl get pods -o wide            (NAME, READY, NODE; hashes trimmed)
account-service       1/1  ledgerflow-worker     account-service       1/1  ledgerflow-worker2
balance-service       1/1  ledgerflow-worker     balance-service       1/1  ledgerflow-worker2
issuer-service        1/1  ledgerflow-worker     issuer-service        1/1  ledgerflow-worker2
ledger-service        1/1  ledgerflow-worker     ledger-service        1/1  ledgerflow-worker2
notification-service  1/1  ledgerflow-worker     notification-service  1/1  ledgerflow-worker2
payment-service       1/1  ledgerflow-worker     payment-service       1/1  ledgerflow-worker2
risk-service          1/1  ledgerflow-worker     risk-service          1/1  ledgerflow-worker2
settlement-service    1/1  ledgerflow-worker     settlement-service    1/1  ledgerflow-worker2
one replica of each service on each worker, 16 + 4, nothing on the control plane. The four infra pods land
where the scheduler puts them: this cluster had postgres and redis on worker, redpanda and lgtm on worker2;
the one rebuilt from nothing below had postgres, redpanda and lgtm all on worker2.

$ kubectl exec deploy/redpanda -- rpk group describe -i <group>     (all seven)
STATE Stable, TOTAL-LAG 0, MEMBERS 30 = 15 listener threads x 2 pods, every group.instance.id
prefixed with the pod name: settlement-service-5bff4df948-s9qv7-org.springframework.kafka.KafkaListenerEndpointContainer_0-0

The first thing that broke: FATAL: sorry, too many clients already, from Flyway, on the second replica
of three services. 16 pods x Hikari's default pool of 10 is 160 connections; Postgres ships with
max_connections=100, and compose's 8 containers never got near it. k8s/infra/postgres.yaml now
starts it with -c max_connections=250; pg_stat_activity shows 146 with all sixteen up.
```

## One base, eight overlays

`kubectl apply -k` is the whole toolchain - kustomize is built into
kubectl, so there is no Helm, no templating language, no chart to
maintain for eight services deployed from one repository by one person.
`k8s/base` holds one Deployment named `service` and one Service named
`service`, written once with the probe paths, resource shape and env
wiring every service shares. `k8s/services/<svc>/kustomization.yaml`,
one per service, patches exactly three things: `namePrefix: <svc>-`,
the image (`ledgerflow/service` to `ledgerflow/<svc>-service`), and a
`DB_URL` plus NodePort patch - risk also patches `DB_USER`/`DB_PASSWORD`
to `risk`, balance carries no `DB_URL` patch at all, because it has no
database. The top-level `k8s/kustomization.yaml` sets the namespace,
lists all eight overlays plus the four infra manifests, and generates
one ConfigMap, `ledgerflow-env`, with every shared address in it -
`KAFKA_BOOTSTRAP`, `OTLP_ENDPOINT`, `REDIS_HOST=redis`, `OLLAMA_URL`,
`ACCOUNT_BASE_URL`, `ISSUER_BASE_URL=http://issuer-service:8080` (every
pod listens on 8080 inside the cluster; issuer's own external port from
step 01 has nothing to do with its in-cluster address), `SERVER_PORT=8080`.
Changing the broker address is one line in one file.

An overlay is allowed a name, an image and a database URL. The day one
needs a fourth kind of patch is the day that service stopped being like
the other seven, not a day to reach for a template variable.

```
kubectl kustomize k8s/ | grep -c 'kind: Deployment'  -- 12
  (account, balance, issuer, ledger, notification, payment, risk, settlement,
   postgres, redpanda, redis, lgtm)
```

## Three probes, three questions

`startup` asks whether the process has finished booting, and suspends the
other two probes while it runs - a JVM's slow first fifteen seconds is
never read as a crash and restarted in a loop. `readiness` asks whether
traffic should land here; failing it pulls the pod out of the Service's
endpoints and kills nothing. `liveness` asks whether the process is
wedged; failing it kills the container. Point liveness at anything that
touches a dependency and one slow database becomes a cluster-wide restart
storm at the exact moment the database can least afford new connections -
Boot's health groups are built for this split: `readinessState,db` (plus
`redis` for risk; `readinessState,redis` alone for balance, which has no
database) for readiness, the bare `livenessState` for liveness, so a
Postgres outage removes pods from Service endpoints and kills none of them.

Base probe shape: `startupProbe` on `/actuator/health/readiness`, period
5s, 30 failures allowed (150s to boot); `readinessProbe` the same path,
period 5s; `livenessProbe` on `/actuator/health/liveness`, period 10s.

Take Postgres away and watch the split hold (a deleted pod is back in six seconds, under what
a readiness flip needs, so the outage below is a scale-to-zero held for a minute):

```
$ kubectl delete pod -l app=postgres            the replacement was Running 6 s later - under the
                                                 15 s a readiness flip needs (period 5 s x failureThreshold 3),
                                                 so nothing left READY. Held it down instead:
$ kubectl scale deploy/postgres --replicas=0     21:44:48
   21:44:58  16/16 READY
   21:45:02   6/16
   21:45:07   2/16  <- and stays there: balance-service x2, whose readiness group is readinessState,redis
$ kubectl scale deploy/postgres --replicas=1     21:45:49
   21:46:02  15/16
   21:46:06  16/16
RESTARTS: unchanged on all sixteen (the column reads 2 on every pod: two Docker Desktop restarts
earlier in the day, none from a probe). Inside a NotReady pod, 25 s into the outage:
$ kubectl get pod settlement-service-... -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}'   False
$ GET /actuator/health/liveness (via /dev/tcp - the run image has no curl)                            HTTP/1.1 200 {"status":"UP"}
Through the NodePort the same probe answered 000 - a NotReady pod is out of the Service's endpoints, so
there was nothing behind port 8087 to answer; kubelet asks the pod directly, which is the point.
```

A rolling restart of a Deployment with a curl loop running against it
during the rollout, watching for the moment readiness is supposed to be
doing its job:

```
curl -s -o /dev/null -w %{http_code} localhost:8087/actuator/health, in a loop, during
$ kubectl rollout restart deploy/settlement-service && kubectl rollout status deploy/settlement-service

                                    samples   200   503   000
without preStop                          85    82     2     1
with preStop: {sleep: {seconds: 5}}     102   102     0     0

Both 503s landed in the second an old pod got SIGTERM: Boot's graceful shutdown flips readiness to
REFUSING_TRAFFIC at once, and kube-proxy takes about a second to stop routing to a pod that has just left
the endpoints; the 000 is the request that was open when that JVM exited. The base Deployment now carries
the native sleep preStop action (GA since 1.30 - no shell needed), which holds SIGTERM until the endpoint
change has propagated. New pods Ready in 6-10 s each; grep -c '^5' on the loop's log = 0.
```

## Requests, limits, and the one we left out

Memory request equals limit - `768Mi` on both, not the textbook `512Mi` -
because step 25 measured 310-417MiB RSS per JVM at `MaxRAMPercentage=70`
inside a 768Mi container under the same bench this step reruns; 512Mi
leaves no headroom for metaspace and thread stacks on top of that heap,
and the kernel OOM-kills a container over its memory limit with no stack
trace anywhere. Request equal to limit also makes the pod Guaranteed
QoS, so it is not the first thing evicted when a node is short on
memory for someone else's spike.

There is no CPU limit, on purpose. A CPU limit throttles rather than
kills, and a throttled JVM under GC pressure is a latency mystery that
looks like anything except what it is - the request (`200m`) is what the
scheduler reserves, and that is the only CPU number this step sets.
`terminationGracePeriodSeconds: 45` gives the graceful-shutdown window
step 25 built (`spring.lifecycle.timeout-per-shutdown-phase: 30s`) five
seconds of margin against Kubernetes' own SIGKILL clock, and `INSTANCE_ID`
comes from the downward API (`metadata.name`) so two replicas of one
service get two Kafka static group instance ids instead of fencing each
other on the port-based id step 25 flagged as the thing to fix here.

## The timetable leaves the application

Kubernetes cron is five fields; Spring's `@Scheduled` is six, because it
carries a seconds field on top. Moving an expression between the two
without noticing is how "nightly" becomes "every minute" - the three
CronJobs here were written fresh against the five-field form rather than
copied: `nightly-settlement` at `5 1 * * *` Europe/London,
`statement-export` at `0 2 * * *`, `aged-item-sweep` at `*/15 * * * *`.

`concurrencyPolicy` has three values and two of them are traps for a
money job. `Allow` runs a second instance alongside the first - the exact
two-schedulers bug step 23 caught with an exception. `Replace` kills the
running Job and starts a new one, which for a settlement run means
killing it mid-write. `Forbid`, used on all three CronJobs here, refuses
to start a new Job while one is still going, before anything runs rather
than after something breaks. `startingDeadlineSeconds: 3600` on the
settlement CronJob means a cluster that was down at 01:05 still runs the
job within the hour rather than simply skipping the night.

The args are Spring properties, not job parameters -
`--spring.batch.job.enabled=true --spring.batch.job.name=nightlySettlementJob
--spring.main.web-application-type=none` - and `businessDate` is computed
at run time rather than baked into the manifest: `command: ["bash","-c"]`,
`args: ["exec /cnb/process/web … businessDate=$(date -d yesterday +%F)"]`.
The buildpack's run image carries bash, `/cnb/process/web` is the same
launcher `docker compose run` used in step 25, and `exec` hands the shell
off to it rather than leaving a wrapper process job's exit code has to
travel through.

`BatchSchedule.java` and the three `settlement.batch.*-cron` yml keys are
deleted, not deprecated - the cluster owns the timetable now, and leaving
both in place is exactly the two-schedulers shape step 23 already paid
for once.

A manual run from the CronJob's own definition, so it is identical to the
scheduled one down to the image and the args:

```
$ kubectl get cronjob nightly-settlement -o jsonpath='{.spec.concurrencyPolicy}'     Forbid
$ kubectl create job settle-now --from=cronjob/nightly-settlement
$ kubectl wait --for=condition=complete job/settle-now --timeout=300s                condition met
$ kubectl get pod -l job-name=settle-now -o jsonpath='{..exitCode}'                  0
$ kubectl logs job/settle-now
21:44:11.725 Started SettlementServiceApplication in 3.457 seconds
21:44:11.728 Running default command line with: [businessDate=2026-09-15]      <- $(date -d yesterday +%F), chosen by the pod
21:44:11.789 Job: [SimpleJob: [name=nightlySettlementJob]] launched ... businessDate=2026-09-15
21:44:11.815 Executing step: [lineItemsStep]          231ms
21:44:12.068 Executing step: [netByMerchantStep]       19ms
21:44:12.110 ... completed ... status: [COMPLETED] in 309ms
20 NEW items seeded for 2026-09-15 -> 20 SETTLED, 20 settlement_line, 5 settlement_batch. Pod alive 7 s for a 309 ms job.
$ kubectl create job sweep-now --from=cronjob/aged-item-sweep                        exit 0, COMPLETED in 50ms, swept 0 rows

Two things the first scheduled ticks showed, both fixed in this step:
- settlement-recover had failed on every tick since the cluster came up: "required a bean of type
  JobRegistry that could not be found". Spring Batch 6's registrar wires a bean *named* jobRegistry into
  the operator if one exists and never creates it; Boot's autoconfig would have, and backs off under
  @EnableBatchProcessing. The web profile never needed one. BatchConfig now declares a MapJobRegistry.
- every job pod joined the settlement-service consumer group. The job is the service image, listeners and
  all, with no INSTANCE_ID in its template, so 15 static members named settlement-service-8080-... came and
  went with each run; a static member that exits without LeaveGroup holds its partitions for
  session.timeout.ms (30 s). rpk showed 45 members and CompletingRebalance straight after sweep-now, back
  to 30 Stable half a minute later - a rebalance per tick, four an hour from the sweep alone.
  --spring.kafka.listener.auto-startup=false on all four CronJobs; the rerun built no consumers.
```

## Three layers of retry

Three retries now, and they answer different questions. `RetryPolicy`
(step 24) is one item against one flaky dependency - milliseconds,
absorbed inside a chunk, invisible in the logs when it succeeds.
`backoffLimit` is Kubernetes deciding the whole pod died - OOMKilled,
evicted, node lost - and starting a completely fresh pod running the
whole job from the top; it knows nothing about chunks or checkpoints,
only about a container that exited non-zero. `restart` (step 24's
`/restart` endpoint, or the CLI's `restart <executionId>`) is a human or
a runbook continuing a specific failed execution from its last committed
chunk.

`backoffLimit` retrying the whole job is only safe because of two things
built earlier and not because Kubernetes is being careful: step 22's job
instance identity (a business date can only ever have one instance) and
step 24's `on conflict do nothing` writer, which makes rewriting an
already-committed chunk a no-op instead of a duplicate. Take either one
away and two pod restarts settle one day three times.

## A pod dies mid-job

`kubectl create job settle-crash --from=cronjob/nightly-settlement`, then
`kubectl delete pod -l job-name=settle-crash --grace-period=0 --force`
while it is running. `backoffLimit` does not know a chunk from a
tablespace - it starts a new pod running the job from the beginning - and
the job repository is left holding the execution the killed pod never
got to finish, `STARTED` with no `end_time`, the same shape step 24
produced by hand with a killed JVM:

```
20000 NEW rows for 2026-09-01; settle-crash from the CronJob's dry-run YAML with businessDate=2026-09-01
(a Job's args are fixed at creation). Pod force-deleted one second after "Executing step: [lineItemsStep]".

batch_job_execution   8 | STARTED | UNKNOWN | end_time NULL
batch_step_execution  lineItemsStep | STARTED | commit_count 2 | write_count 200      <- two chunks of 100 committed, the third in flight

$ kubectl get job settle-crash -o jsonpath='{.status.failed} {.status.succeeded}'   (backoffLimit 2)
22:48:56  failed=1  active=1   settle-crash-9cv8r  Error
22:49:02  failed=2             settle-crash-9cv8r  Error
22:49:17  failed=2  active=1   settle-crash-g4drm  Running
22:49:23  failed=3             settle-crash-g4drm  Error      conditions: Failed, reason BackoffLimitExceeded

Both retry pods exit 1 within a second of starting the job:
  JobExecutionAlreadyRunningException: A job execution for this job is already running: JobInstance: id=8
Kubernetes retried the pod as designed; the repository refused every retry because execution 8 still says
STARTED. That is the stranded case - a retry cannot get past it, only recovery can.
```

The recovery path is not the curl-based sweeper a first draft would
reach for. A CronJob whose only container is `curlimages/curl` posting to
`settlement-service`'s own `/recover-stranded` endpoint depends on the
service it exists to fix being reachable, which is exactly backwards for
a pod that may have just been evicted along with everything else on its
node. `settlement-recover-cron.yaml` runs the same settlement image
instead, `--spring.profiles.active=cli --spring.main.web-application-type=none
recover-stranded`, no HTTP anywhere in the path - it works with the
settlement Deployment scaled to zero, because it never asks that
Deployment for anything.

Run it that way - the point is it not needing the service to be up:

```
$ kubectl scale deploy/settlement-service --replicas=0             0 settlement pods
$ kubectl create job recover-now --from=cronjob/settlement-recover
$ kubectl wait --for=condition=complete job/recover-now            condition met, exit 0
21:49:54.975 Started SettlementServiceApplication in 2.925 seconds
21:49:55.000 CommandLineJobOperator | Recovering job execution with ID: 8
21:49:55.012 TaskExecutorJobOperator | Recovering job execution: JobExecution: id=8, status=STARTED, endTime=null

select job_execution_id, status, end_time from batch_job_execution order by 1 desc limit 4
 8 | FAILED    | 2026-09-16 21:49:55.018      <- was STARTED, NULL
 7 | COMPLETED | 2026-09-16 21:46:39.826
 6 | COMPLETED | 2026-09-16 21:45:04.255
 5 | COMPLETED | 2026-09-16 21:44:54.435
select count(*) from batch_job_execution where status='STARTED' and end_time is null     0

$ kubectl scale deploy/settlement-service --replicas=2
And the same Job definition once more (same businessDate, so the same JobInstance): execution 9 COMPLETED,
lineItemsStep read 19800 / write 19800 / 198 commits, 16.5 s - it began at the 200 rows execution 8 had
committed. 20000 SETTLED, 20000 settlement_line: nothing settled twice, nothing lost.
```

## The operator as a command

`BatchCliConfig`, `@Profile("cli")`, wires Spring Batch 6's
`CommandLineJobOperator` to Boot's own `ApplicationArguments` rather than
calling the operator's static `main` - that builds a second Spring
context, and this step wants one context wearing three hats: a web
service, a one-shot job runner, and now an operator console. The
non-option arguments are `start <job> [k=v…]`, `stop|restart|abandon|
recover <executionId>`, and `recover-stranded`, which is filtered to one
job name (`jobRepository.findRunningJobExecutions("nightlySettlementJob")`,
then `recover` on each) - a money job gets a narrow sweeper on purpose,
not one pointed at every job this service will ever run.

The exit code is the operator's own: 0 completed, 1 failed, 2 a
configuration class it couldn't find - stored in an `ExitCodeGenerator`
bean the runner reads on the way out, the same contract
`SpringApplication.exit(ctx)` has been giving the one-shot job path since
step 25.

Why this exists next to the HTTP endpoints from steps 22 and 24: a
runbook that asks a possibly-dead service to fix itself over HTTP has a
circular dependency built into it. The CLI needs a JVM to start and a
database to reach - nothing else.

## From nothing

`scripts/k8s-up.sh`: create the cluster if it is not already there,
create and select the namespace, `scripts/build-images.sh`, `kind load
docker-image` eight times, apply the Secret and ConfigMap through
`--dry-run=client -o yaml | kubectl apply -f -` (idempotent either way),
`kubectl apply -k k8s/`, `kubectl apply -f k8s/jobs/`, `kubectl wait
--for=condition=Available deploy --all --timeout=600s`, then
`scripts/topics.sh` and `scripts/check-schemas.sh --register` through the
same mapped ports the README already uses, and a final `echo "cluster up"`.
`scripts/k8s-down.sh` is one line, `kind delete cluster --name ledgerflow`.

Timed end to end, from a cluster that does not exist to one Kubernetes
calls Available:

```
$ scripts/k8s-down.sh                                   22:51:27 -> 22:51:33   (6 s)
$ scripts/k8s-up.sh                                     22:51:33 -> 23:06:28   "cluster up" after 895 s, 14m55s
   kind create cluster                    ~1 min    (node image cached)
   scripts/build-images.sh                ~6 min    (install + eight build-image, every layer cached)
   kind load docker-image x 8             ~5 min    (~1 GB each, into three nodes; the namespace is empty until the last one)
   apply -k, apply -f k8s/jobs/, wait, topics + schemas   ~3 min
At "cluster up": 20/20 pods 1/1, 11 subjects registered, the README payment Captured in 4 s.

Every service pod restarted four times inside those three minutes: apply -k lands infra and services
together, Postgres takes ~40 s to accept connections, and a service that boots first fails Flyway and
exits 1 - CrashLoopBackOff's 10/20/40/80 s ladder is what the wait is mostly waiting for. Kubernetes
converges on it; an initContainer polling pg_isready would make the boot quieter, not faster.

Step 25's demo-compose.sh from nothing: 8m46s. The difference is the eight loads - a registry turns
them into one push and a pull per node.

The proof, on the fresh cluster:
$ kubectl create job settle-proof --from=cronjob/nightly-settlement       complete, exit 0
select count(*) from batch_job_execution where status='COMPLETED'         1
```

## Checkpoint

`perf/bench.sh step26` runs through the NodePorts this time, not through
compose's host ports directly, against two replicas of every service -
the first checkpoint in this curriculum with two of everything running
at once. `docker stats --no-stream` once during the payments phase,
against the three `kind` nodes rather than eight named containers, is
the closest this step gets to step 25's per-service breakdown; kube-proxy
sits between a curl and a pod in a way `docker compose`'s bridge network
did not.

```
perf/bench.sh step26 (PSQL="kubectl exec deploy/postgres -- psql" - run.sh/settled.sh read the payment database
through that now, the way topics.sh takes RPK), commit f99f918-dirty, k6 on the host through the NodePorts:

scenario   load            p50    p95    p99      failed
transfer   100/s for 60s   4ms    7ms    22ms     0%        (step 25: 4 / 6 / 9, 4.31% failed)
holds      50/s for 30s    9ms    64ms   1261ms   0%        (step 25: 7 / 14 / 78)
payments   100/s for 180s  5ms    8ms    12ms     0%        (step 25: 5 / - / 33)
settled                    243ms  331ms  378ms    captured 18005, failed 0     (step 25: 316 / - / 3351)

The holds p99 is warm-up, not the cluster: the ledger pods were 3.5 minutes old with no /holds traffic
behind them, and two replicas means two cold JIT profiles to pay for at 50/s instead of one; 15 of 1501
requests took over a second, max 1.9 s. The same scenario again on the same pods, warm
(perf/run.sh holds warm26): p50 7ms, p95 10ms, p99 12ms, max 62ms.

docker stats --no-stream, during the payments phase (two samples, 23:10:33 and 23:11:25):
ledgerflow-control-plane   cpu  12% /  12%    mem  954MiB           net 15MB / 33MB
ledgerflow-worker          cpu 251% / 176%    mem 3.44GiB           net 164MB / 269MB
ledgerflow-worker2         cpu 424% / 318%    mem 7.60GiB           net 2.32GB / 184MB
worker2 holds postgres, redpanda and lgtm as well as its eight service pods, which is where the memory
and the inbound bytes go; the control plane does nothing but the API server, as it should.

Settled p99 3351 -> 378 ms with the same 18005 captured. The p50s match compose (5 ms per request,
243 vs 316 ms settled); what changed is the tail. Two of everything is the one difference in the path -
every saga hop has a second consumer to land on - but this step did not isolate it: a one-replica bench
on the same cluster would, and is the first thing to run if that number is ever doubted.
```

Step 25, for the number this is measured against: settled p50 316ms,
p99 3351ms, 18005 captured, 0 failed, one replica per service, straight
compose networking.

## Kept

- `k8s/base` plus one overlay per service - the shape that keeps a probe
  path or a resource number from drifting across eight copies.
- The three-probe split and the health groups that back it:
  `readinessState,db` (or `,redis`) for readiness, the bare
  `livenessState` for liveness.
- CronJobs with `Forbid` for the timetable, and `BatchSchedule.java`
  deleted rather than left to run alongside them.
- `CommandLineJobOperator` behind a `cli` profile, and
  `settlement-recover-cron.yaml` running that image directly instead of
  a curl sidecar.

Not kept:

- A curl-image sweeper hitting an HTTP endpoint to recover a service that
  may be the thing that just went down.
- `512Mi` memory limits - step 25's own RSS numbers rule them out before
  the first bench of this step runs.
- Port 8086 as issuer's in-cluster address - every pod listens on 8080,
  and an overlay that assumed otherwise would have shipped a ConfigMap
  no pod could reach.
