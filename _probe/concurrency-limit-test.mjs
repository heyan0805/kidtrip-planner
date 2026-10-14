import { FLYAI_CLI } from './_paths.mjs'
// 抓并发失败的原始错误，并测试"限制并发数"是否能避免。
import { execFile } from 'node:child_process';

const NODE = process.env.TEST_NODE || 'node';
const B = FLYAI_CLI;

const CALLS = [
  ['search-poi', '--city-name', '深圳', '--category', '动物园'],
  ['search-poi', '--city-name', '深圳', '--category', '主题乐园'],
  ['search-poi', '--city-name', '深圳', '--category', '水上乐园'],
  ['search-poi', '--city-name', '深圳', '--category', '博物馆'],
  ['search-poi', '--city-name', '深圳', '--category', '海洋馆'],
  ['search-hotel', '--dest-name', '深圳'],
];

function run(args) {
  return new Promise((resolve) => {
    const t0 = Date.now();
    execFile(NODE, [B, ...args], { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 }, (err, stdout, stderr) => {
      let n = -1;
      try { const j = JSON.parse(stdout); n = j.data ? (j.data.itemList || []).length : 0; } catch { }
      resolve({
        ms: Date.now() - t0,
        n,
        exit: err ? (err.code ?? 'ERR') : 0,
        cmd: args.slice(2).join(' '),
        errText: (stderr || err?.message || '').replace(/\s+/g, ' ').slice(0, 300),
      });
    });
  });
}

// 并发池：最多同时跑 limit 个
async function pool(limit) {
  const t0 = Date.now();
  const out = [];
  let i = 0;
  async function worker() {
    while (i < CALLS.length) {
      const k = i++;
      out[k] = await run(CALLS[k]);
    }
  }
  await Promise.all(Array.from({ length: Math.min(limit, CALLS.length) }, worker));
  return { ms: Date.now() - t0, out };
}

function show(label, r) {
  const bad = r.out.filter((x) => x.n < 0);
  console.log(`--- ${label}  墙钟 ${r.ms}ms  失败 ${bad.length}/${r.out.length} ---`);
  r.out.forEach((x) => {
    const flag = x.n < 0 ? '✗' : '✓';
    console.log(`  ${flag} ${String(x.ms).padStart(5)}ms exit=${String(x.exit).padStart(4)} 条数=${String(x.n).padStart(3)}  ${x.cmd}`);
  });
  bad.forEach((x) => console.log(`      ↳ 错误原文: ${x.errText || '(stderr 为空)'}`));
  console.log();
}

show('并发 6（全部同时）', await pool(6));
show('并发 3（限流）', await pool(3));
show('并发 1（串行）', await pool(1));
