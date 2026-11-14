// 生成余额解析的期望值（旧版 balance.js 的实跑结果）。
// 用法：node tools/balance-golden/gen.cjs
// 只在「旧版 balance.js 变了」或「新增服务商」时重跑；期望值写在 BalanceCoreTest.kt 里。
const B = require('../../app/src/main/assets/www/balance.js')

const cases = [
  ['deepseek', 200, JSON.stringify({ is_available: true, balance_infos: [{ currency: 'CNY', total_balance: '88.80', granted_balance: '8.80', topped_up_balance: '80.00' }] })],
  ['deepseek', 200, JSON.stringify({ is_available: true, balance_infos: [] })],
  ['deepseek', 401, JSON.stringify({ error: { message: 'invalid key' } })],
  ['siliconflow', 200, JSON.stringify({ code: 200, data: { totalBalance: '12.3456', balance: '10.0000', chargeBalance: '2.3456' } })],
  ['moonshot', 200, JSON.stringify({ data: { available_balance: 5.5, voucher_balance: 1.5, cash_balance: 4.0 } })],
  ['openrouter', 200, JSON.stringify({ data: { label: 'sk', limit: 10, limit_remaining: 7.25, usage: 2.75 } })],
  ['custom', 200, '{}'],
]
for (const [preset, status, text] of cases) {
  console.log(JSON.stringify(['parse', preset, status, B.parseBalanceResponse(preset, status, text)]))
}

const reqs = [
  { presetId: 'deepseek', baseUrl: 'https://api.deepseek.com', hasKey: true, apiKey: '' },
  { presetId: 'siliconflow', baseUrl: 'https://api.siliconflow.cn/v1/', hasKey: true },
  { presetId: 'moonshot', baseUrl: 'https://api.moonshot.cn/v1', hasKey: true },
  { presetId: 'openrouter', baseUrl: 'https://openrouter.ai/api/v1', hasKey: true },
  { presetId: 'custom', baseUrl: 'http://x', hasKey: true },
  { presetId: 'deepseek', baseUrl: 'https://api.deepseek.com', hasKey: false },
]
for (const p of reqs) console.log(JSON.stringify(['req', B.buildBalanceRequest(p)]))

console.log(JSON.stringify(['deepseekRoot', B.deepseekRoot('https://api.deepseek.com/v1/'), B.deepseekRoot('https://api.deepseek.com')]))
