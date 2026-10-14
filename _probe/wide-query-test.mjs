import { FLYAI_CLI } from './_paths.mjs'
// 测「一次宽查 + 本地筛」是否可行：不带 --category 查一次，看结果的多样性和数量够不够排 3 天行程。
import { execFile } from 'node:child_process';

const NODE = process.env.TEST_NODE || 'node';
const B = FLYAI_CLI;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function run(args) {
  return new Promise((resolve) => {
    execFile(NODE, [B, ...args], { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 }, (err, stdout, stderr) => {
      let j = null;
      try { j = JSON.parse(stdout); } catch { }
      const code = /HTTP (\d{3})/.exec(stderr || '')?.[1] ?? (err ? 'ERR' : '200');
      resolve({ ok: !err, code, list: j?.data?.itemList ?? [], err: (stderr || '').replace(/\s+/g, ' ').slice(0, 200) });
    });
  });
}

function analyze(label, r) {
  if (!r.ok) { console.log(`--- ${label}  ✗ 上游码=${r.code}  ${r.err}`); return; }
  const l = r.list;
  const byCat = {};
  l.forEach((x) => { byCat[x.category] = (byCat[x.category] || 0) + 1; });
  const withPrice = l.filter((x) => x.ticketInfo).length;
  console.log(`--- ${label}  ✓ 共 ${l.length} 条，有票价 ${withPrice} 条 ---`);
  console.log(`    分类分布: ${JSON.stringify(byCat)}`);
  console.log(`    前 6 个: ${l.slice(0, 6).map((x) => x.name + '(' + x.category + ')').join('、')}`);
}

await sleep(Number(process.env.COOLDOWN || 45) * 1000);

analyze('A. 深圳 · 不带 category（宽查）', await run(['search-poi', '--city-name', '深圳']));
await sleep(8000);
analyze('B. 深圳 · keyword=亲子（宽查）', await run(['search-poi', '--city-name', '深圳', '--keyword', '亲子']));
await sleep(8000);
analyze('C. 深圳 · poi-level 5（不传 category）', await run(['search-poi', '--city-name', '深圳', '--poi-level', '5']));
