// ponytail: one-shot converter, not a maintained tool. Re-run by hand if study-platform content changes.
const fs = require('fs');
const path = require('path');

const STUDY = 'D:/projects/practice/java/study-platform/tracks';
const OUT = 'D:/projects/practice/microservices/ledgerflow/ide-trainer/experiments';
const LETTERS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ';

const TRACKS = [
  { id: 'java-professional', name: 'Java Professional' },
  { id: 'dsa-interview', name: 'DSA Interview Prep' },
  { id: 'spring-professional', name: 'Spring Professional' },
  { id: 'career-plan', name: 'Career Sprint' },
];

function readTrackYaml(dir) {
  const text = fs.readFileSync(path.join(dir, 'track.yaml'), 'utf8');
  const displayName = /displayName:\s*(.+)/.exec(text)?.[1]?.trim() ?? '';
  const examCode = /examCode:\s*(.+)/.exec(text)?.[1]?.trim() ?? '';
  return { displayName, examCode };
}

// Table rows: | [~] | 01 | [01-slug](../modules/01-slug/) | Objective text |
function parseModuleRows(md) {
  const rows = [];
  const re = /^\|\s*\[[^\]]*\]\s*\|\s*(\d+)\s*\|\s*\[([^\]]+)\]\([^)]+\)\s*\|\s*(.+?)\s*\|\s*$/gm;
  let m;
  while ((m = re.exec(md))) rows.push({ num: m[1], slug: m[2], objective: m[3].trim() });
  return rows;
}

function parseSyllabus(md) {
  // Split on "## Phase N — Name (k modules)" headers; if none, one implicit stage.
  const phaseRe = /^##\s*Phase\s*\d+\s*—\s*(.+?)\s*\(\d+\s*modules?\)\s*$/gm;
  const phases = [];
  let last = { name: null, start: 0 };
  let m;
  const heads = [];
  while ((m = phaseRe.exec(md))) heads.push({ name: m[1].trim(), index: m.index, end: m.index + m[0].length });
  if (heads.length === 0) {
    return [{ name: null, rows: parseModuleRows(md) }];
  }
  // Stop each phase's slice at "## Progress log" or the next phase header.
  const progressLogIdx = md.search(/^##\s*Progress log/m);
  for (let i = 0; i < heads.length; i++) {
    const start = heads[i].end;
    let end = i + 1 < heads.length ? heads[i + 1].index : md.length;
    if (progressLogIdx !== -1 && progressLogIdx < end && progressLogIdx > start) end = progressLogIdx;
    phases.push({ name: heads[i].name, rows: parseModuleRows(md.slice(start, end)) });
  }
  return phases;
}

function checklistTasks(modDir) {
  const p = path.join(modDir, 'objectives-checklist.md');
  if (!fs.existsSync(p)) return [];
  const md = fs.readFileSync(p, 'utf8');
  const items = [];
  const re = /^-\s*\[ \]\s*(.+)$/gm;
  let m;
  while ((m = re.exec(md))) items.push(m[1].trim());
  return items.map((line) => ({ do: line }));
}

function buildTrack(trackId, displayName) {
  const trackDir = path.join(STUDY, trackId);
  const syllabusPath = path.join(trackDir, 'curriculum', 'SYLLABUS.md');
  const md = fs.readFileSync(syllabusPath, 'utf8');
  const phases = parseSyllabus(md);

  const stages = phases.map((phase, i) => {
    const steps = phase.rows.map((row) => {
      const modDir = path.join(trackDir, 'modules', row.slug);
      const tasks = checklistTasks(modDir);
      return {
        id: row.num,
        title: row.slug.replace(/^\d+-/, '').replace(/-/g, ' '),
        goal: row.objective,
        tasks: tasks.length ? tasks : [{ do: `Study ${row.slug}` }],
      };
    });
    return {
      letter: LETTERS[i] ?? String(i + 1),
      name: phase.name ?? displayName,
      steps,
    };
  });

  return { curriculum: { project: displayName, stack: '', stages } };
}

for (const t of TRACKS) {
  const { displayName } = readTrackYaml(path.join(STUDY, t.id));
  const name = displayName || t.name;
  const data = buildTrack(t.id, name);

  const dir = path.join(OUT, t.id);
  fs.mkdirSync(dir, { recursive: true });
  const dataFile = path.join(dir, `${t.id}.json`);
  fs.writeFileSync(dataFile, JSON.stringify(data, null, 2));

  const manifest = {
    name,
    board: '',
    data: dataFile.replace(/\\/g, '/'),
    currentStepPath: 'progress.summary.currentStep',
    lessons: [],
  };
  fs.writeFileSync(path.join(dir, 'experiment.json'), JSON.stringify(manifest, null, 2));

  const stepCount = data.curriculum.stages.reduce((s, st) => s + st.steps.length, 0);
  const taskCount = data.curriculum.stages.reduce(
    (s, st) => s + st.steps.reduce((s2, step) => s2 + step.tasks.length, 0), 0
  );
  console.log(`${t.id}: ${data.curriculum.stages.length} stages, ${stepCount} steps, ${taskCount} tasks`);
}
