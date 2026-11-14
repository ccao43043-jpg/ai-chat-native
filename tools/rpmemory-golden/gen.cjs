// 生成 rpmemory golden：直接用旧版 JS 跑一遍，Kotlin 侧逐字比对。
// 用法：node tools/rpmemory-golden/gen.cjs
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../..');
const R = require(path.join(root, 'app/src/main/assets/www/rpmemory.js'));

const entries = [
  { id: 'a1', seq: 1, type: 'fact', text: '林月是船长，住在北港。', keywords: ['林月', '船长', '北港'], pinned: false, disabled: false },
  { id: 'a2', seq: 2, type: 'event', text: '昨天船队遇上了风暴。', keywords: ['船队', '风暴'], pinned: false, disabled: false },
  { id: 'a3', seq: 3, type: 'relation', text: '林月信任老周。', keywords: ['林月', '老周', '信任'], pinned: true, disabled: false },
  { id: 'a4', seq: 4, type: 'detail', text: '货舱里有一只上锁的铁箱。', keywords: ['货舱', '铁箱'], pinned: false, disabled: false },
  { id: 'a5', seq: 5, type: 'fact', text: '这艘船叫「白鲸号」。', keywords: ['白鲸号', '船'], pinned: false, disabled: false },
];

const out = {
  autoKeywords: R.autoKeywords('林月在北港的码头等老周，手里拿着账本。Lin Yue waits.'),
  mergeKeywords: R.mergeKeywords(['林月', '船长', '北港'], ['林月', '风暴', '船长']),
  triples: ['林月信任老周。', '林月不信任老周了。', '铁箱上锁了。', '这句话没有关系词。']
    .map(t => R.extractRelationTriple(t)),
  conflicts: [
    ['林月信任老周。', '林月不信任老周。'],
    ['林月信任老周。', '林月信任小李。'],
    ['铁箱上锁了。', '铁箱解锁了。'],
    ['林月是船长。', '林月不是船长。'],
  ].map(([a, b]) => ({ a, b, hit: R.detectConflict(a, b) })),
  mergeSimple: (() => {
    const r = R.mergeEntries(entries.slice(0, 3), [
      { type: 'fact', text: '林月是船长，住在北港的灯塔旁。', keywords: ['林月', '灯塔'] },
      { type: 'event', text: '船队修好了帆。', keywords: ['船队', '帆'] },
    ], 1700000000000, 'auto', { turn: 7 });
    return { added: r.added, merged: r.merged, conflicts: r.conflicts, superseded: r.superseded, list: r.list };
  })(),
  mergeConflict: (() => {
    const r = R.mergeEntries(
      [{ id: 'a3', seq: 3, type: 'relation', text: '林月信任老周。', keywords: ['林月', '老周'], disabled: false, version: 1 }],
      [{ type: 'relation', text: '林月不信任老周了。', keywords: ['林月', '老周'] }],
      1700000005000, 'auto', { turn: 9 },
    );
    return { added: r.added, conflicts: r.conflicts, superseded: r.superseded, list: r.list };
  })(),
  selectBm25: R.selectRelevant(entries, '林月 老周 信任', { memTokens: 600, maxInject: 3 }).map(e => e.id),
  selectLegacy: R.selectRelevant(entries, '货舱 铁箱', { retrieval: 'legacy', maxInject: 2 }).map(e => e.id),
  injection: R.renderInjection(R.selectRelevant(entries, '林月 信任', { maxInject: 2 })),
  memoryText: R.toMemoryText(entries.slice(0, 2)),
  parsed: R.parseExtraction('```json\n[{"type":"事实","text":"林月是船长","keywords":["林月"]},{"type":"event","text":"遇上了风暴"}]\n```'),
  parsedLines: R.parseExtraction('- [关系] 林月信任老周\n- [细节] 铁箱上锁了'),
  shouldExtract: [R.shouldExtract(20, 0, 20), R.shouldExtract(19, 0, 20), R.shouldExtract(40, 20, 20)],
  settings: R.settingsOf({ interval: 3, maxInject: 99, retrieval: 'x', window: 1, memTokens: 99999 }),
  promptHead: R.buildExtractPrompt(
    [{ role: 'user', content: '你好' }, { role: 'assistant', content: '你好，船长。' }],
    [{ type: 'fact', text: '林月是船长' }],
  ).split('\n').slice(0, 6),
};

const dest = path.join(root, 'app/src/test/resources/golden/rpmemory/core.json');
fs.mkdirSync(path.dirname(dest), { recursive: true });
fs.writeFileSync(dest, JSON.stringify(out, null, 2) + '\n');
console.log('wrote', dest, fs.statSync(dest).size, 'bytes');
