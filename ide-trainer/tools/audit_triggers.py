"""Check an experiment's trigger ids against what an installed IDE declares.

Action and tool-window ids drift between releases, and a lesson pointing at a
renamed id silently never fires — the worst failure mode this design has, since
it looks like the learner simply hasn't done the thing yet. This reads the ids
out of the IDE's own jars so the check is offline and costs nothing at runtime.

    python ide-trainer/tools/audit_triggers.py \
        "C:/Program Files/JetBrains/IntelliJ IDEA 2026.2.1" \
        ide-trainer/experiments/ledgerflow/experiment.json

Takes a few minutes: it opens every jar in the installation.
"""
import json
import os
import re
import sys
import zipfile

DEFAULT_IDE = r"C:/Program Files/JetBrains/IntelliJ IDEA 2026.2.1"
DEFAULT_EXP = "ide-trainer/experiments/ledgerflow"

# Registered in code rather than XML, so scanning descriptors cannot see them.
# Verified against the constant pool of ToolWindowId / *Executor classes.
CODE_REGISTERED_TOOLWINDOWS = {"Services", "Debug", "Run"}
EXECUTOR_IDS = {"Run", "Debug", "Coverage"}

ACTION_RE = re.compile(rb'<action[^>]*\bid="([^"]+)"')
TOOLWINDOW_RE = re.compile(rb'<toolWindow[^>]*\bid="([^"]+)"')


def scan(ide_path):
    actions, toolwindows, jars = set(), set(), 0
    for root, _, files in os.walk(ide_path):
        for f in files:
            if not f.endswith(".jar"):
                continue
            try:
                with zipfile.ZipFile(os.path.join(root, f)) as z:
                    for name in z.namelist():
                        if not name.endswith(".xml"):
                            continue
                        data = z.read(name)
                        actions.update(m.decode() for m in ACTION_RE.findall(data))
                        toolwindows.update(m.decode() for m in TOOLWINDOW_RE.findall(data))
                jars += 1
            except Exception:
                pass
    return actions, toolwindows | CODE_REGISTERED_TOOLWINDOWS, jars


def collect_lessons(exp_dir):
    """Lessons live in the step file they teach, one file per step."""
    out = []
    spine = json.load(open(os.path.join(exp_dir, "curriculum.json"), encoding="utf-8"))
    for stage in spine.get("stages", []):
        for fname in stage.get("steps", []):
            path = os.path.join(exp_dir, "steps", fname)
            if os.path.isfile(path):
                out.extend(json.load(open(path, encoding="utf-8")).get("lessons", []))
    return out


def main():
    ide = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_IDE
    exp_path = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_EXP
    if not os.path.isdir(ide):
        sys.exit(f"no IDE at {ide}")
    if not os.path.isdir(exp_path):
        sys.exit(f"no experiment folder at {exp_path}")

    actions, toolwindows, jars = scan(ide)
    print(f"scanned {jars} jars: {len(actions)} action ids, {len(toolwindows)} tool window ids\n")

    lessons = collect_lessons(exp_path)
    bad = 0
    for lesson in lessons:
        kind, tid = lesson["trigger"]["type"], lesson["trigger"]["id"]
        known = {"action": actions, "toolWindow": toolwindows, "run": EXECUTOR_IDS}.get(kind)
        ok = True if known is None else tid in known   # fileOpen patterns are ours
        if ok:
            print(f"ok       {lesson['id']:24} {kind}:{tid}")
        else:
            bad += 1
            near = sorted(a for a in known if tid.lower() in a.lower() or a.lower() in tid.lower())[:6]
            print(f"MISSING  {lesson['id']:24} {kind}:{tid}   near: {near}")

    print(f"\n{bad} trigger(s) not found in this IDE build")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
