import { FLYAI_CLI } from './_paths.mjs'
import { execFile } from 'node:child_process';
const CLI = FLYAI_CLI;
const run = (a) => new Promise(r => execFile('node', [CLI, ...a], { maxBuffer: 1 << 26 },
  (e, so) => r({ out: so, code: e ? e.code : 0 })));
const { out } = await run(['search-train','--origin','广州','--destination','珠海',
  '--dep-date','2026-11-17','--back-date','2026-11-22','--sort-type','4']);
let j = null; try { j = JSON.parse(out); } catch {}
console.log('status=', j?.status, 'message=', j?.message);
const list = j?.data?.itemList;
console.log('命中', list?.length);
if (list?.length) {
  const it = list[0];
  console.log('顶层字段：', Object.keys(it).join(', '));
  console.log('journeys 条数 =', it.journeys?.length);
  for (const jr of (it.journeys || [])) {
    const s = jr.segments?.[0] || {};
    console.log(`  journey ${jr.journeyType} total=${jr.totalDuration} ${s.depStationShortName}${s.depDateTime} → ${s.arrStationShortName}${s.arrDateTime}`);
  }
  console.log('price=', it.price, ' totalDuration=', it.totalDuration);
}
