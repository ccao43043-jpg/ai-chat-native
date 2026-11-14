// 生成世界书触发层的 golden（跑的是仓库里的旧版 rpcache.js，不是重写实现）。
//   node tools/lore-golden/gen.cjs
// 输出 app/src/test/resources/golden/rpcache/lore.json
const fs = require('node:fs');
const path = require('node:path');
const R = require(path.join(__dirname, '../../app/src/main/assets/www/rpcache.js'));

const OUT = path.join(__dirname, '../../app/src/test/resources/golden/rpcache/lore.json');

function st() {
  const s = R.newState ? R.newState() : {};
  const lore = R.parseLore([
    '## 港口 | *',
    '海风很咸。',
    '',
    '## 灯塔 | 灯塔, 灯 | priority=200; sticky=2; cooldown=1',
    '红光每十五秒转一圈。',
    '',
    '## 老船工 | 船工, 老人 | sec=渔网; delay=2',
    '他右手少了两根手指。',
    '',
    '## 潮汐 | 潮水 | prob=40',
    '涨潮时不让人下海。',
  ].join('\n'));
  return Object.assign({}, s, { lore, settings: Object.assign({}, s.settings, { max_context: 4000 }) });
}

const state = st();
const items = [
  { id: 'u1', role: 'user', content: '我想去看看灯塔。' },
  { id: 'a1', role: 'assistant', content: '灯塔的光扫过海面。' },
  { id: 'u2', role: 'user', content: '船工说渔网破了。' },
];

const fired = R.fireLore(state, items, 1700000000000, { convId: 'c1' });
const after = fired.state;
const itemsAfter = R.applyFrozenLore(after, items);

const out = {
  cyrb53: [R.seedHash('c1\u0000u2\u0000e1'), R.seedHash('')],
  roll: (() => {
    const rng = R.rngForSeed('c1\u0000u2\u0000x');
    return [0, 1, 2, 3].map(() => Math.round(rng() * 1e6) / 1e6);
  })(),
  matchKey: [
    R.activateLore(state, [{ id: 'u9', role: 'user', content: 'LIGHTHOUSE 灯塔 at night' }], { turn: 1, convId: 'c' }).map(e => e.name),
  ],
  constantLoreText: R.constantLoreText(state),
  firePicked: fired.fired.map(e => ({ entryId: e.entryId, anchorId: e.anchorId, name: e.name })),
  firedText: itemsAfter.map(m => ({ id: m.id, content: m.content })),
  loreState: { turn: after.loreState.turn, entries: Object.keys(after.loreState.entries).sort().map(k => ({
    id: k,
    stickyUntil: after.loreState.entries[k].stickyUntil,
    cooldownUntil: after.loreState.entries[k].cooldownUntil,
    lastAnchorId: after.loreState.entries[k].lastAnchorId,
  })) },
  // 第二轮：同一批条目不应重复触发（anchor 变了才会再触发）
  secondPick: R.fireLore(after, itemsAfter, 1700000001000, { convId: 'c1' }).fired.map(e => e.name),
};

fs.mkdirSync(path.dirname(OUT), { recursive: true });
fs.writeFileSync(OUT, JSON.stringify(out, null, 2) + '\n', 'utf8');
console.log('wrote', OUT);
