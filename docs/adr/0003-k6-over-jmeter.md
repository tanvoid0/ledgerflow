# ADR 0003: k6 over JMeter for the load tests

Status: accepted (step 15)

## Context

The numbers in `docs/perf/` are as much the deliverable as the code: they
have to be reproducible by anyone who clones the repo, comparable across
months of commits while the system grows from one service to seven, and
readable in a diff. The obvious choice would be JMeter, which is what most
teams reach for and most interviewers expect.

## Options considered

1. **JMeter.** The standard. But a test plan is a `.jmx` XML file edited
   through a GUI: a diff of two plans is unreadable, so a change in what was
   measured hides from review. Its default model is closed (N threads in a
   loop): when the server slows down the threads slow down with it, and the
   load drops exactly when the number matters. An open, constant-arrival
   model needs a third-party plugin. It runs on the JVM, next to the seven
   JVMs it is measuring, on the same machine.
2. **Gatling.** Code-first, open model built in, good reports. Scala or Java
   DSL, a JVM, and a build step; heavier than the job needs.
3. **k6.** A test is fifty lines of JavaScript in git, reviewed like any
   other code. `constant-arrival-rate` is a built-in executor, so a stall
   shows up in the percentiles instead of throttling the load. Thresholds
   make a run pass or fail (`p(99)<1000`), the summary is JSON, and the
   binary is a single Go executable with no JVM to compete with the
   services for CPU.

## Decision

k6. Every scenario is a file in `perf/k6/`, every run a JSON summary in
`docs/perf/` stamped with the commit, date, host and load it measured
(`perf/run.sh`). `perf/bench.sh stepNN` runs the same three scenarios at
the same load at the end of every step, and `perf/history.sh` turns the
directory into `docs/perf/README.md`, tables plus a p99 line per scenario
across the checkpoints. A reader gets the trend from the page and any
single number from `jq` on the raw file.

k6 stops at the HTTP response. For the payment saga that is a 202 a few
milliseconds in, and the payment finishes seconds later, three services
away. `perf/settled.sh` reads that from the saga table with Postgres as the
one clock and adds it to the run as `payment_settled`, so the end-to-end
number sits next to the request number in the same file.

## Consequences

- No GUI. Someone who wants to see a run graphed reads the generated page,
  or points Grafana at k6's Prometheus output later; nothing here prevents
  that.
- The checkpoints are only comparable while the load stays fixed. Changing
  the suite in `bench.sh` starts a new series; the old rows keep their
  `load` column so the break is visible, not silent.
- One machine, services as local JVMs. Absolute numbers are that machine's;
  the shape across steps is the claim.
