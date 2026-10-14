import { FLYAI_CLI } from './_paths.mjs'
import { execFile } from 'node:child_process';
const CLI = FLYAI_CLI;
const run = (args) => new Promise((res) => {
  execFile('node', [CLI, ...args], { maxBuffer: 1 << 26 }, (e, so, se) =>
    res({ out: so, err: se, code: e ? e.code : 0 }));
});
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

function probe(label, args) {
  const { out } = { out: '' };
  return run(args).then(({ out, err, code }) => {
    let j = null; try { j = JSON.parse(out); } catch { }
    const list = j?.data?.itemList;
    console.log(`\n=== ${label}  [exit ${code}]`);
    console.log(`    命令: search-${args[0]} ${args.slice(1).join(' ')}`);
    if (!list) { console.log(`    data=null  message=${j?.message} status=${j?.status}  stderr=${(err||'').trim().slice(0,120)}`); return; }
    console.log(`    命中 ${list.length} 条`);
    for (const it of list.slice(0, 4)) {
      const seg = it.journeys?.[0]?.segments?.[0] || {};
      console.log(`    · ${seg.depStationShortName}→${seg.arrStationShortName}  ` +
        `${seg.marketingTransportName || ''}${seg.marketingTransportNo || ''}  ` +
        `${seg.depDateTime?.slice(11,16)} → ${seg.arrDateTime?.slice(11,16)}  ` +
        `耗时 ${it.totalDuration} 分  中转 ${it.journeys[0].journeyType}  ¥${it.price}`);
    }
  });
}

await probe('A 广州→珠海  不限时长', ['search-train', '--origin', '广州', '--destination', '珠海', '--dep-date', '2026-11-17', '--sort-type', '4']);
await sleep(7000);
await probe('B 广州→桂林  限 ≤3 小时', ['search-train', '--origin', '广州', '--destination', '桂林', '--dep-date', '2026-11-17', '--total-duration-hour', '3', '--sort-type', '4']);
await sleep(7000);
await probe('C 广州→桂林  不限时长', ['search-train', '--origin', '广州', '--destination', '桂林', '--dep-date', '2026-11-17', '--sort-type', '4']);
await sleep(7000);
await probe('D 珠海→桂林  限 ≤3 小时', ['search-train', '--origin', '珠海', '--destination', '桂林', '--dep-date', '2026-11-18', '--total-duration-hour', '3', '--sort-type', '4']);
