import { FLYAI_CLI } from './_paths.mjs'
/**
 * 城市名实测 —— 回答一个问题：
 *
 *   用户说"广东周边"，飞猪到底认不认这个 --city-name？
 *
 * 背景：上一轮用户在界面上真实走了一遍，画像 city="广东周边"，
 * 于是 search-poi 返回 0 条、search-hotel 也 0 条，整份行程排不出来。
 *
 * 但"返回 0 条"有两种可能，必须分清：
 *   ① 飞猪**不认识**"广东周边"这个 city（参数非法 → 静默返回空）
 *   ② 飞猪认识，但那个区域真的没有数据
 * 两者的修法完全不同，所以要用真实请求把它区分开。
 *
 * 对照设计：
 *   A. 广东周边  —— 用户原话（区域描述）
 *   B. 广东      —— 省级名，看不看？
 *   C. 珠海      —— 我们期望模型该给的"具体城市"
 *   D. 广州      —— 另一个具体城市，排除"珠海碰巧没数据"
 *
 * 串行 + 间隔（实测并发会被 429/451）。
 * 用法：node city-test.mjs   （GAP=秒 COOLDOWN=秒 可调）
 */

import { execFile } from 'node:child_process';

const NODE = process.env.TEST_NODE || 'node';
const B = FLYAI_CLI;
const GAP = Number(process.env.GAP || 9) * 1000;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function run(args) {
  return new Promise((resolve) => {
    execFile(NODE, [B, ...args], { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 }, (err, stdout, stderr) => {
      let j = null;
      try { j = JSON.parse(stdout); } catch { }
      resolve({
        ok: !err,
        code: /HTTP (\d{3})/.exec(stderr || '')?.[1] ?? (err ? 'ERR' : '200'),
        envelopeStatus: j?.status,
        message: j?.message,
        systemMessage: j?.systemMessage,
        list: j?.data?.itemList ?? [],
        err: (stderr || '').replace(/\s+/g, ' ').slice(0, 160),
      });
    });
  });
}

function brief(label, r, showTop = 3) {
  if (!r.ok) {
    console.log(`── ${label}  ✗ code=${r.code}  ${r.err}`);
    return;
  }
  console.log(`── ${label}  ✓ ${r.list.length} 条  status=${r.envelopeStatus}  message=${JSON.stringify(r.message)}`);
  if (r.systemMessage) console.log(`     systemMessage: ${JSON.stringify(r.systemMessage).slice(0, 200)}`);
  r.list.slice(0, showTop).forEach((x) => {
    console.log(`     · ${(x.name || '?').slice(0, 32).padEnd(34)} ${x.category ? '[' + x.category + ']' : ''} price=${x.price ?? '-'}`);
  });
}

const CITY_CASES = ['广东周边', '广东', '珠海', '广州'];

console.log(`城市名对照 —— search-poi --city-name <X>，串行间隔 ${GAP / 1000}s`);
console.log(`先冷却 ${Number(process.env.COOLDOWN || 20) / 1000}s…\n`);
await sleep(Number(process.env.COOLDOWN || 20) * 1000);

for (const city of CITY_CASES) {
  const r = await run(['search-poi', '--city-name', city]);
  brief(`search-poi --city-name "${city}"`, r);
  await sleep(GAP);
}

// 酒店那一侧也验一次 —— 用户这次酒店也是 0 条，看是不是同一个原因
console.log('\n════ 酒店侧对照 ════');
for (const city of ['广东周边', '珠海']) {
  const r = await run(['search-hotel', '--dest-name', city, '--check-in-date', '2026-11-17', '--check-out-date', '2026-11-23']);
  brief(`search-hotel --dest-name "${city}"`, r);
  await sleep(GAP);
}

console.log('\n完成。');
