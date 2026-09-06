"""Scaffold a step file, and lint the content that already exists.

A step lives in `experiments/<path>/steps/NN-slug.json` and is listed by that
path's `curriculum.json`. Both halves have to agree, and every task needs a key
unique within its step — which is exactly the kind of bookkeeping worth not
doing by hand.

    # add a step to the end of stage B, with three empty tasks
    python ide-trainer/tools/step.py new git B "rebasing safely" --tasks 3

    # add a task to a step that exists (keys generated from the text)
    python ide-trainer/tools/step.py add-task git 07 "Squash the review fixups"

    # every path: keys present, keys unique, ids unique, files listed and present
    python ide-trainer/tools/step.py check

`check` is the same invariant `ExperimentTest` pins, runnable without Gradle;
`new` and `add-task` run it afterwards, so a scaffold that broke something says
so immediately.
"""
import argparse
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(os.path.dirname(HERE), "experiments")
TAGS = re.compile(r"<[^>]*>")
STOP = {"a", "an", "the", "and", "or", "of", "to", "in", "it", "is", "for", "with", "your", "you"}


def slug(text, taken=(), fallback="task"):
    words = [w for w in re.split(r"[^A-Za-z0-9]+", TAGS.sub(" ", text or "").lower()) if w]
    words = [w for w in words if w not in STOP] or words
    base = ("-".join(words[:5]) or fallback)[:48].strip("-") or fallback
    out, n = base, 2
    while out in taken:
        out, n = f"{base}-{n}", n + 1
    return out


def read(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def write(path, obj):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, indent=2, ensure_ascii=False)
        f.write("\n")


def paths(name=None):
    names = [name] if name else sorted(os.listdir(ROOT))
    for n in names:
        d = os.path.join(ROOT, n)
        if os.path.isfile(os.path.join(d, "curriculum.json")):
            yield n, d


# ---- check ---------------------------------------------------------------

def coverage(name, d, cur, files):
    steps = [read(os.path.join(d, "steps", f)) for f in files if os.path.isfile(os.path.join(d, "steps", f))]
    tasks = [t for st in steps for t in st.get("tasks", [])]
    return {
        "path": name,
        "steps": len(steps),
        "tasks": len(tasks),
        "blocks": sum(1 for t in tasks if t.get("blocks")),
        "checks": sum(1 for t in tasks if t.get("check")),
        "why": sum(1 for t in tasks if t.get("why")),
        "lessons": sum(len(st.get("lessons", [])) for st in steps),
        "setup": "setup" in cur,
        "plan": "plan" in cur,
        "cheats": os.path.isfile(os.path.join(d, "cheatsheet.json")),
    }


def check(name=None):
    problems, rows = [], []
    for n, d in paths(name):
        cur = read(os.path.join(d, "curriculum.json"))
        ids, files = {}, set()
        for stage in cur.get("stages", []):
            for fname in stage.get("steps", []):
                path = os.path.join(d, "steps", fname)
                if fname in files:
                    problems.append(f"{n}: {fname} is listed twice")
                files.add(fname)
                if not os.path.isfile(path):
                    problems.append(f"{n}: {fname} is listed but missing")
                    continue
                step = read(path)
                sid = step.get("id")
                if not sid:
                    problems.append(f"{n}/{fname}: no id")
                elif sid in ids:
                    problems.append(f"{n}: step id {sid} used by both {ids[sid]} and {fname}")
                else:
                    ids[sid] = fname
                keys = [t.get("key") for t in step.get("tasks", [])]
                for i, k in enumerate(keys):
                    if not k:
                        problems.append(f"{n}/{fname}: task {i} has no key")
                if len(set(keys)) != len(keys):
                    dupes = sorted({k for k in keys if keys.count(k) > 1 and k})
                    problems.append(f"{n}/{fname}: duplicate key(s) {dupes}")
        on_disk = set(os.listdir(os.path.join(d, "steps"))) if os.path.isdir(os.path.join(d, "steps")) else set()
        for orphan in sorted(on_disk - files):
            problems.append(f"{n}: steps/{orphan} exists but no stage lists it")
        rows.append(coverage(n, d, cur, sorted(files)))

    # What each path actually uses, not just whether it is valid: a bare
    # checklist is legal and renders, but the IDE has nothing to run, verify or
    # teach in it. CONTENT-ROADMAP.md is this table with the argument attached.
    head = f"{'path':22}{'steps':>6}{'tasks':>7}{'blocks':>8}{'checks':>8}{'why':>6}{'lessons':>9}{'setup':>7}{'plan':>6}{'cheats':>8}"
    print(head)
    print("-" * len(head))
    for r in rows:
        print(f"{r['path']:22}{r['steps']:>6}{r['tasks']:>7}{r['blocks']:>8}{r['checks']:>8}"
              f"{r['why']:>6}{r['lessons']:>9}{str(r['setup']):>7}{str(r['plan']):>6}{str(r['cheats']):>8}")
    print()

    for p in problems:
        print(f"  ! {p}")
    print(f"\n{len(problems)} problem(s)")
    return 1 if problems else 0


# ---- new -----------------------------------------------------------------

def next_id(d):
    ids = []
    for stage in read(os.path.join(d, "curriculum.json")).get("stages", []):
        for fname in stage.get("steps", []):
            f = os.path.join(d, "steps", fname)
            if os.path.isfile(f):
                ids.append(read(f).get("id", ""))
    numeric = [int(i) for i in ids if i.isdigit()]
    return f"{max(numeric) + 1:02d}" if numeric else "01"


def new_step(name, letter, title, tasks, estimate, goal):
    d = dict(paths(name)).get(name) or sys.exit(f"no such path: {name} (looked in {ROOT})")
    cur_path = os.path.join(d, "curriculum.json")
    cur = read(cur_path)
    stage = next((s for s in cur.get("stages", []) if s.get("letter") == letter), None)
    if stage is None:
        sys.exit(f"{name} has no stage {letter} (has: {[s.get('letter') for s in cur.get('stages', [])]})")

    sid = next_id(d)
    fname = f"{sid}-{slug(title, fallback='step')}.json"
    path = os.path.join(d, "steps", fname)
    if os.path.exists(path):
        sys.exit(f"{path} already exists")

    step = {
        "id": sid,
        "title": title,
        "estimate": estimate,
        "goal": goal or "",
        "startWith": "",
        "newIdeas": [],
        # Placeholder keys: replace the text AND the key together while the
        # step is still unticked; once anyone has ticked it, keep the key.
        "tasks": [{"key": f"task-{i + 1}", "do": ""} for i in range(tasks)],
        "doneWhen": "",
        "lessons": [],
    }
    write(path, step)
    stage.setdefault("steps", []).append(fname)
    write(cur_path, cur)
    print(f"created steps/{fname} (id {sid}) and listed it under stage {letter}\n")
    return check(name)


def add_task(name, step_id, text):
    d = dict(paths(name)).get(name) or sys.exit(f"no such path: {name}")
    for stage in read(os.path.join(d, "curriculum.json")).get("stages", []):
        for fname in stage.get("steps", []):
            path = os.path.join(d, "steps", fname)
            if os.path.isfile(path) and read(path).get("id") == step_id:
                step = read(path)
                taken = {t.get("key") for t in step.get("tasks", [])}
                key = slug(text, taken)
                step.setdefault("tasks", []).append({"key": key, "do": text})
                write(path, step)
                print(f"added {key} to steps/{fname}\n")
                return check(name)
    sys.exit(f"{name} has no step {step_id}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    n = sub.add_parser("new", help="scaffold a step file and list it")
    n.add_argument("path")
    n.add_argument("stage", help="stage letter, e.g. B")
    n.add_argument("title")
    n.add_argument("--tasks", type=int, default=3)
    n.add_argument("--estimate", default="45 min")
    n.add_argument("--goal", default="")

    a = sub.add_parser("add-task", help="append a task with a generated key")
    a.add_argument("path")
    a.add_argument("step", help="step id, e.g. 07")
    a.add_argument("text")

    c = sub.add_parser("check", help="lint every path, or one")
    c.add_argument("path", nargs="?")

    args = ap.parse_args()
    if args.cmd == "new":
        return new_step(args.path, args.stage, args.title, args.tasks, args.estimate, args.goal)
    if args.cmd == "add-task":
        return add_task(args.path, args.step, args.text)
    return check(args.path)


if __name__ == "__main__":
    sys.exit(main())
