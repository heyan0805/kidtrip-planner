import { FLYAI_CLI } from './_paths.mjs'
import { execFile } from 'node:child_process';
const CLI = FLYAI_CLI;
const run = (args) => new Promise((res) => {
  execFile('node', [CLI, ...args], { maxBuffer: 1 << 26 }, (e, so, se) =>
    res({ out: so, err: se, code: e ? e.code : 0 }));
});
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

async function probe(label, sub, args) {
  const { out, err, code } = await run([sub, ...args]);
  let j = null; try { j = JSON.parse(out); } catch { }
  const list = j?.data?.itemList;
  console.log(`\n=== ${label}  [exit ${code}]`);
  console.log(`    ${sub} ${args.join(' ')}`);
  if (!list) { console.log(`    data=null message=${j?.message} status=${j?.status} err=${(err||'').trim().slice(0,100)}`); return; }
  console.log(`    命中 ${list.length} 条`);
  for (const it of list.slice(0, 3)) {
    const jr = it.journeys?.[0] || {};
    const segs = jr.segments || [];
    const path = segs.map(s => s.depStationShortName || s.depCityName).concat(segs.at(-1)?.arrStationShortName || segs.at(-1)?.arrCityName).join('→');
    console.log(`    · ${path}  ${jr.journeyType}  ${segs[0]?.depDateTime?.slice(11,16)}→${segs.at(-1)?.arrDateTime?.slice(11,16)}  ` +
      `总耗时 ${it.totalDuration} 分  中转等待 ${it.transferDuration || '-'}  ¥${it.price}`);
  }
}

await probe('E 珠海→桂林 要走中转', 'search-train', ['--origin','珠海','--destination','桂林','--dep-date','2026-11-18','--journey-type','2','--sort-type','4']);
await sleep(7000);
await probe('F 桂林→广州 直飞', 'search-flight', ['--origin','桂林','--destination','广州','--dep-date','2026-11-22','--journey-type','1','--sort-type','4']);
await sleep(7000);
await probe('G 广州→汕头 ≤3h', 'search-train', ['--origin','广州','--destination','汕头','--dep-date','2026-11-19','--total-duration-hour','3','--sort-type','4']);
await sleep(7000);
await probe('H 珠海→汕头 ≤3h', 'search-train', ['--origin','珠海','--destination','汕头','--dep-date','2026-11-19','--total-duration-hour','3','--sort-type','4']);
