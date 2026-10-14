import { FLYAI_CLI } from './_paths.mjs'
/**
 * 补两个必答问题：
 *
 *   ① **过去日期的杀伤力** —— 上一轮日志里 startDate=2025-11-17（今天是 2026-10-13）。
 *      城市探针用 2026 年日期查"广东周边"酒店拿到 10 条，而真实请求（2025 年日期）拿到 0 条。
 *      如果日期是原因，那"酒店 0 条"就不只是城市名的锅 —— 是两个错叠加。
 *
 *   ② **非法城市的"沉默"程度** —— 我们要判断能不能靠报错发现它：
 *      如果飞猪对垃圾城市名返回的是 HTTP 错误，那好办；
 *      如果它 status=0 / message="empty" 地静默返回空，那就必须先校验再发出去。
 *
 * 用法：node date-and-city-test.mjs
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
        status: j?.status,
        message: j?.message,
        systemMessage: j?.systemMessage,
        list: j?.data?.itemList ?? [],
        err: (stderr || '').replace(/\s+/g, ' ').slice(0, 160),
      });
    });
  });
}

function brief(label, r) {
  if (!r.ok) {
    console.log(`── ${label}\n     ✗ HTTP code=${r.code}  ${r.err}`);
    return;
  }
  console.log(`── ${label}\n     ✓ ${r.list.length} 条  status=${r.status}  message=${JSON.stringify(r.message)}`);
  r.list.slice(0, 3).forEach((x) => console.log(`       · ${(x.name || '?').slice(0, 30)} price=${x.price ?? '-'} star=${x.star ?? '-'}`));
}

console.log(`串行间隔 ${GAP / 1000}s，先冷却 ${Number(process.env.COOLDOWN || 20) / 1000}s…\n`);
await sleep(Number(process.env.COOLDOWN || 20) * 1000);

console.log('════ ① 日期对酒店的影响（同一城市，只改日期）════');
brief('珠海 · 过去日期 2025-11-17→23',
  await run(['search-hotel', '--dest-name', '珠海', '--check-in-date', '2025-11-17', '--check-out-date', '2025-11-23']));
await sleep(GAP);
brief('珠海 · 未来日期 2026-11-17→23',
  await run(['search-hotel', '--dest-name', '珠海', '--check-in-date', '2026-11-17', '--check-out-date', '2026-11-23']));

console.log('\n════ ② 景点侧：非法城市是报错还是静默返回空？════');
await sleep(GAP);
brief('search-poi --city-name "深圳周边"（我 prompt 里写的示例！）',
  await run(['search-poi', '--city-name', '深圳周边']));
await sleep(GAP);
brief('search-poi --city-name "asdfgh"（纯垃圾）',
  await run(['search-poi', '--city-name', 'asdfgh']));

console.log('\n完成。');
