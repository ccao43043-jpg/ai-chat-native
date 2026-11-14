// 生成 rpcache 章节摘要的 golden：直接跑旧版 www/rpcache.js，把结果写成 JSON。
// 跑法：node tools/rpcache-golden/gen-chapter.cjs
const fs = require('fs');
const path = require('path');

const R = require('../../app/src/main/assets/www/rpcache.js');

const good = [
  'FACTS:',
  '- 林月是北港的船长，船名「白鹭」。',
  '- 风暴发生在三天前的夜里。',
  '',
  'CHARACTER_KNOWLEDGE:',
  '- 林月：知道铁箱里有一封未寄出的信。',
  '- 老周：只知道船队受损，不知道信的存在。',
  '',
  'PLAYER_KNOWLEDGE:',
  '- 玩家见过信上的火漆印。',
  '',
  'SECRET:',
  '- 信是写给已故的港务长的，对老周保密。',
  '',
  'UNRESOLVED:',
  '- 谁在风暴夜打开了舱门，尚未查清。',
  '',
  'RELATIONSHIPS:',
  '- 林月与老周：由合作转为互相猜忌。',
  '',
  'OBJECTS:',
  '- 铁箱：林月保管，钥匙在老周手里。',
  '',
  'LOCATION:',
  '- 北港：栈桥受损，灯塔正常。',
].join('\n');

const minimalButInvalid = 'FACTS:\n- 只有一条事实。';
const withFence = '```markdown\n[CHAPTER SUMMARY #003]\n' + good + '\n```';

const batch = [
  { id: 'm1', role: 'user', content: '船还好吗？' },
  { id: 'm2', role: 'assistant', content: '桅杆断了，帆还能补。' },
  { id: 'm3', role: 'user', content: '铁箱呢？' },
  { id: 'm4', role: 'assistant', content: '锁着，钥匙在老周那儿。' },
];

const chapters = [
  { no: 1, content: 'FACTS:\n- 船队从北港出发。' },
  { no: 2, content: 'FACTS:\n- 遇上风暴。' },
];

const out = {
  sections: R.parseSummary(good).text && ['FACTS', 'CHARACTER_KNOWLEDGE', 'PLAYER_KNOWLEDGE', 'SECRET', 'UNRESOLVED', 'RELATIONSHIPS', 'OBJECTS', 'LOCATION'],
  good: R.parseSummary(good),
  fenced: R.parseSummary(withFence),
  invalid: R.parseSummary(minimalButInvalid),
  empty: R.parseSummary(''),
  qualityGood: R.parseSummary(good).quality,
  qualityInvalid: R.parseSummary(minimalButInvalid).quality,
  prompt: R.buildSummaryPrompt({ chapters, batch, memory: '- [事实] 林月是船长。' }),
  promptNoChapters: R.buildSummaryPrompt({ chapters: [], batch }),
  consolidate: R.buildConsolidatePrompt({ chapters, batch, memory: '' }),
  transcript: batch.map(m => (m.role === 'user' ? '【玩家】' : '【角色】') + m.content.trim()).join('\n\n'),
  sourceFp: R.parseSummary(good).ok, // 只用来确认解析通过，sourceFp 需要 st，不做 golden
};

const dir = path.join(__dirname, '../../app/src/test/resources/golden/rpcache');
fs.mkdirSync(dir, { recursive: true });
fs.writeFileSync(path.join(dir, 'chapter.json'), JSON.stringify(out, null, 2) + '\n', 'utf8');
console.log('wrote chapter.json');
console.log(JSON.stringify({ okGood: out.good.ok, qGood: out.good.quality, qInvalid: out.invalid.quality, missingInvalid: out.invalid.missing.length }, null, 2));
