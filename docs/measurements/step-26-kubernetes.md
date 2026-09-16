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
{{R: nodes}}
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

Delete the Postgres pod and watch the split hold:

```
{{R: probes}}
```

A rolling restart of a Deployment with a curl loop running against it
during the rollout, watching for the moment readiness is supposed to be
doing its job:

```
{{R: roll}}
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
{{R: cron}}
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
{{R: kill}}
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
{{R: recover}}
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
{{R: up}}
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
{{R: bench}}
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
