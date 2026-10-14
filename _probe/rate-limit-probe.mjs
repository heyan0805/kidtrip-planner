import { FLYAI_CLI } from './_paths.mjs'
// 摸清上游的限流窗口：冷却一段时间后，按固定间隔串行调用，看第几次开始被拦。
import { execFile } from 'node:child_process';

const NODE = process.env.TEST_NODE || 'node';
const B = FLYAI_CLI;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const CALLS = [
  ['search-poi', '--city-name', '深圳', '--category', '动物园'],
  ['search-poi', '--city-name', '珠海', '--category', '主题乐园'],
  ['search-hotel', '--dest-name', '深圳'],
  ['search-poi', '--city-name', '广州', '--category', '博物馆'],
];

function run(args) {
  return new Promise((resolve) => {
    const t0 = Date.now();
    execFile(NODE, [B, ...args], { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 }, (err, stdout, stderr) => {
      let n = -1;
      try { const j = JSON.parse(stdout); n = j.data ? (j.data.itemList || []).length : 0; } catch { }
      const msg = (stderr || '').replace(/\s+/g, ' ');
      const code = /HTTP (\d{3})/.exec(msg)?.[1] ?? (err ? 'ERR' : '200');
      resolve({ ms: Date.now() - t0, n, code, ok: !err, msg: msg.slice(0, 160) });
    });
  });
}

const COOLDOWN_S = Number(process.env.COOLDOWN || 90);
const GAP_S = Number(process.env.GAP || 6);

console.log(`冷却 ${COOLDOWN_S} 秒（让限流窗口过期）...`);
await sleep(COOLDOWN_S * 1000);
console.log('开始：串行，每次间隔 ' + GAP_S + ' 秒\n');

for (let i = 0; i < CALLS.length; i++) {
  const r = await run(CALLS[i]);
  const flag = r.ok ? '✓' : '✗';
  console.log(`  第 ${i + 1} 次 ${flag}  ${String(r.ms).padStart(5)}ms  上游码=${r.code}  条数=${String(r.n).padStart(3)}  ${CALLS[i].slice(1).join(' ')}`);
  if (!r.ok) console.log(`         ↳ ${r.msg}`);
  if (i < CALLS.length - 1) await sleep(GAP_S * 1000);
}
