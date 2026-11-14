/*
 * 生成 rpcache 纯函数的 golden 期望值（用旧版 JS 当真值来源）。
 *
 * 用法（仓库根目录，Node 18+）：
 *   node tools/rpcache-golden/gen-golden.cjs
 * 输出：
 *   app/src/test/resources/golden/rpcache/core.json
 *
 * 说明：app/src/main/assets/www/ 里的 JS 是行为基准（见 docs/NATIVE_REWRITE_SPEC.md），
 * 这里不打包、不运行于 App，只在开发时用来录 golden。
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..', '..');
const WWW = path.join(ROOT, 'app/src/main/assets/www');
const OUT = path.join(ROOT, 'app/src/test/resources/golden/rpcache/core.json');

const R = require(path.join(WWW, 'rpcache.js'));

const set = (st, id, content) => R.setBlock(st, id, content, 1700000000000).state;

// F1：只有 base system，没有任何块
const f1 = R.newState();

// F2：全部静态块 + 长期记忆 + 两个章节 + 增量 + 状态
let f2 = R.newState();
f2 = set(f2, 'world', '蒸汽朋克港城，永夜，齿轮与锅炉。');
f2 = set(f2, 'character', '薇拉，机械师，19 岁，说话简短。');
f2 = set(f2, 'rules', 'NPC 不能说破玩家的心思。');
f2 = set(f2, 'memory', '玩家在第三章救过薇拉。');
f2 = set(f2, 'delta', '薇拉刚刚提到她怕水。');
f2 = set(f2, 'state', '时间：第 4 天清晨｜地点：锅炉房｜情绪：戒备');
f2 = R.addChapter(f2, { content: '第一章：玩家进城，与薇拉在废料场相遇。', coveredIds: ['m1', 'm2'] });
f2 = R.addChapter(f2, { content: '第二章：两人一起修好了升降机。', coveredIds: ['m3', 'm4'] });

const hist = [
  { id: 'm5', role: 'user', content: '你会游泳吗？' },
  { id: 'm6', role: 'assistant', content: '薇拉皱眉：“在水边长大的人，未必喜欢水。”' },
  { id: 'm7', role: 'user', content: '那我们绕开码头走。' },
];
const input = { id: 'm8', role: 'user', content: '你怕水是因为那次事故吗？' };

const providers = [
  { presetId: 'deepseek', baseUrl: 'https://api.deepseek.com', type: 'openai' },
  { presetId: 'qwen', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', type: 'openai' },
  { presetId: 'openrouter', baseUrl: 'https://openrouter.ai/api/v1', type: 'openai' },
  { presetId: 'anthropic', baseUrl: 'https://api.anthropic.com', type: 'anthropic' },
  { presetId: 'gemini', baseUrl: 'https://generativelanguage.googleapis.com/v1beta', type: 'gemini' },
  { presetId: 'custom', baseUrl: 'http://10.0.2.2:8787/v1', type: 'openai' },
];
const models = ['deepseek-chat', 'qwen3.7-plus', 'qwen-max', 'claude-3-7-sonnet', 'gemini-2.0-flash', 'gpt-4o', 'mock-1'];

const caps = [];
for (const p of providers) {
  for (const m of models) {
    caps.push({ provider: p.presetId, baseUrl: p.baseUrl, type: p.type, model: m, cap: R.capability(p, m), info: R.contextInfo(m, null) });
  }
}

const budgets = ['deepseek-chat', 'qwen3.7-plus', 'claude-3-7-sonnet', 'gemini-2.0-flash', 'mock-1'].map(m => ({
  model: m,
  budget: R.autoBudget(R.capability(providers[0], m), m, null),
}));

const out = {
  generated_from: 'app/src/main/assets/www/rpcache.js',
  cases: {
    f1: {
      system: R.buildSystem(f1, '你是一位角色扮演助手。'),
      segments: R.buildSystemSegments(f1, '你是一位角色扮演助手。'),
      stablePrefix: R.systemStablePrefix(f1, '你是一位角色扮演助手。'),
      finalUser: R.finalUserContent(f1, '你好'),
      tokens: { zh: R.estimateTokens('你好，世界'), en: R.estimateTokens('hello world'), mix: R.estimateTokens('第 4 天清晨，锅炉房 hello'), empty: R.estimateTokens('') },
      history: R.serializeHistory(hist, null),
    },
    f2: {
      system: R.buildSystem(f2, '你是一位角色扮演助手。'),
      segments: R.buildSystemSegments(f2, '你是一位角色扮演助手。'),
      stablePrefix: R.systemStablePrefix(f2, '你是一位角色扮演助手。'),
      finalUser: R.finalUserContent(f2, '你怕水是因为那次事故吗？'),
      staticText: R.staticText(f2, '你是一位角色扮演助手。'),
      summaryText: R.summaryText(f2),
      memoryText: R.memoryText(f2),
      deltaText: R.deltaText(f2),
      stateText: R.stateText(f2),
      estimateItems: R.estimateItems(hist.concat([input])),
      history: R.serializeHistory(hist, 2),
      assembled: (() => {
        const p = R.plan(f2, '你是一位角色扮演助手。', hist.concat([input]), { provider: 'deepseek', model: 'deepseek-chat' });
        const a = R.assemble(p);
        return {
          system: a.system,
          messages: a.messages.map(m => ({ role: m.role, content: m.content })),
          tokens: p.tokens,
          overBudget: p.overBudget,
          trimmed: p.trimmed,
        };
      })(),
    },
    capability: caps,
    budget: budgets,
  },
};

fs.mkdirSync(path.dirname(OUT), { recursive: true });
fs.writeFileSync(OUT, JSON.stringify(out, null, 1) + '\n', 'utf8');
console.log('wrote ' + OUT + ' (' + fs.statSync(OUT).size + ' bytes)');
