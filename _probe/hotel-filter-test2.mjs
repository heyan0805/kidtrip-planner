import { FLYAI_CLI } from './_paths.mjs'
/**
 * 酒店筛选 第二轮实测 —— 三个必答问题：
 *
 *   ① 带**真实入离日期**时（生产就是这么调的），--hotel-stars 还生效吗？
 *      不带日期的"参考价"和带日期的"实时报价"很可能是两条不同的上游路径。
 *
 *   ② 返回体里 star / rate 到底有没有值？
 *      第一轮探针里星级全是"-"，但我的字段名猜错了（该读 rate，我读的 rating）。
 *      这决定 HotelStay.stars / rating 能不能填上，还是永远 null。
 *
 *   ③ 小城市 + 星级筛会不会直接**返回 0 条**？
 *      深圳有货不代表桂林有货。如果是 0 条，就必须给 fallback（去掉星级重查一次），
 *      否则用户会看到"没有查到可用的酒店"——而我们本来是有酒店的。
 *
 * 用法：node hotel-filter-test2.mjs
 */

import { execFile } from 'node:child_process';

const NODE = process.env.TEST_NODE || 'node';
const B = FLYAI_CLI;
const GAP = Number(process.env.GAP || 9) * 1000;
const CI = '2026-10-24';
const CO = '2026-10-27';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function run(args) {
  return new Promise((resolve) => {
    execFile(NODE, [B, ...args], { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 }, (err, stdout, stderr) => {
      let j = null;
      try { j = JSON.parse(stdout); } catch { }
      resolve({
        ok: !err,
        code: /HTTP (\d{3})/.exec(stderr || '')?.[1] ?? (err ? 'ERR' : '200'),
        data: j?.data,
        list: j?.data?.itemList ?? [],
        err: (stderr || '').replace(/\s+/g, ' ').slice(0, 200),
      });
    });
  });
}

const BAD = ['公寓', '客栈', '青旅', '青年旅舍', '青年公寓', '日租', '短租', '民宿', '旅馆', '招待所'];
const isBad = (n) => BAD.some((w) => (n || '').includes(w));
const money = (s) => (s == null || String(s).trim() === '' ? '-' : String(s).trim());

function brief(label, r) {
  if (!r.ok) { console.log(`── ${label}  ✗ 上游码=${r.code}  ${r.err}`); return; }
  const l = r.list;
  const bad = l.filter((x) => isBad(x.name));
  console.log(`── ${label}  ✓ ${l.length} 条，不适合亲子 ${bad.length} 条`);
  l.slice(0, 6).forEach((x) => console.log(`     ${(x.name || '?').padEnd(30)} star=${money(x.star)} rate=${x.rate ?? '-'} price=${money(x.price)} ${x.brandName ? '[' + x.brandName + ']' : ''}`));
}

console.log(`第二轮${' '}—— 带真实日期 ${CI}→${CO}，串行间隔 ${GAP / 1000}s`);
console.log(`先冷却 ${Number(process.env.COOLDOWN || 25) / 1000}s…`);
await sleep(Number(process.env.COOLDOWN || 25) * 1000);

// ① 生产路径：带日期 + 星级筛，dump 原始字段
const r1 = await run(['search-hotel', '--dest-name', '深圳', '--check-in-date', CI, '--check-out-date', CO, '--hotel-stars', '4,5']);
console.log('\n════ ① 深圳 + 日期 + stars=4,5 ════');
brief('深圳（带日期 + stars=4,5）', r1);
if (r1.list[0]) {
  console.log('\n  第一条原始 JSON（看字段名和真实值）：');
  console.log('  ' + JSON.stringify(r1.list[0], null, 2).split('\n').join('\n  '));
}

// ② 深圳基线（带日期），确认筛选确实改变了集合
await sleep(GAP);
const r2 = await run(['search-hotel', '--dest-name', '深圳', '--check-in-date', CI, '--check-out-date', CO]);
console.log('\n════ ② 深圳 + 日期（基线，无星级筛）════');
brief('深圳（带日期，基线）', r2);

// ③ 小城市风险：桂林 + 星级筛是否会空
await sleep(GAP);
const r3 = await run(['search-hotel', '--dest-name', '桂林', '--check-in-date', CI, '--check-out-date', CO, '--hotel-stars', '4,5']);
console.log('\n════ ③ 小城市（桂林）+ stars=4,5 ════');
brief('桂林（带日期 + stars=4,5）', r3);

// ④ 桂林基线
await sleep(GAP);
const r4 = await run(['search-hotel', '--dest-name', '桂林', '--check-in-date', CI, '--check-out-date', CO]);
console.log('\n════ ④ 小城市（桂林）基线 ════');
brief('桂林（带日期，基线）', r4);

console.log('\n完成。');
