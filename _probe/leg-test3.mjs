import { FLYAI_CLI } from './_paths.mjs'
import { execFile } from 'node:child_process';
const CLI = FLYAI_CLI;
const run = (a) => new Promise(r => execFile('node', [CLI, ...a], { maxBuffer: 1 << 26 },
  (e, so) => r({ out: so, code: e ? e.code : 0 })));
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
async function probe(o, d, day) {
  const args = ['search-train', '--origin', o, '--destination', d, '--dep-date', day,
    '--total-duration-hour', '3', '--sort-type', '4'];
  const { out, code } = await run(args);
  let j = null; try { j = JSON.parse(out); } catch { }
  const list = j?.data?.itemList;
  if (!list) { console.log(`${o} → ${d}   ✗ 无边   status=${j?.status}  message=${j?.message}  [exit ${code}]`); return; }
  const it = list[0]; const seg = it.journeys[0].segments[0];
  console.log(`${o} → ${d}   ✓ 有边   ${it.totalDuration} 分  ¥${it.price}  ` +
    `${seg.marketingTransportName}${seg.marketingTransportNo} ${seg.depStationShortName}${seg.depDateTime.slice(11,16)}→${seg.arrStationShortName}${seg.arrDateTime.slice(11,16)}  （共${list.length}班）`);
}
const pairs = [['珠海','深圳','2026-11-18'],['深圳','汕头','2026-11-19'],['汕头','厦门','2026-11-20'],['广州','厦门','2026-11-19']];
for (const [o,d,day] of pairs) { await probe(o,d,day); await sleep(7000); }
