import { FLYAI_CLI } from './_paths.mjs'
import { execFile } from 'node:child_process';
const CLI = FLYAI_CLI;
const run = (a) => new Promise(r => execFile('node', [CLI, ...a], { maxBuffer: 1 << 26 },
  (e, so) => r({ out: so, code: e ? e.code : 0 })));
async function t(label, args) {
  const { out } = await run(args);
  let j = null; try { j = JSON.parse(out); } catch {}
  const list = j?.data?.itemList;
  console.log(`\n${label}`);
  console.log(`  status=${j?.status} message=${j?.message} 命中=${list?.length ?? 'data=null'}`);
  if (list?.length) {
    const it = list[0];
    const legs = (it.journeys||[]).map(jr => `${jr.segments[0].depStationShortName}→${jr.segments[0].arrStationShortName} ${jr.totalDuration}分`);
    console.log(`  第一条：${legs.join('  ／  ')}  合计 totalDuration=${it.totalDuration}  ¥${it.price}`);
  }
}
await t('A 广州↔珠海 往返回 total-duration-hour=1（单段53/50，合计103）', ['search-train','--origin','广州','--destination','珠海','--dep-date','2026-11-17','--back-date','2026-11-22','--total-duration-hour','1','--sort-type','4']);
await new Promise(r=>setTimeout(r,7000));
await t('B 广州↔珠海 往返回 total-duration-hour=3', ['search-train','--origin','广州','--destination','珠海','--dep-date','2026-11-17','--back-date','2026-11-22','--total-duration-hour','3','--sort-type','4']);
await new Promise(r=>setTimeout(r,7000));
await t('C 珠海↔桂林 往返回 total-duration-hour=3（去回都无直达）', ['search-train','--origin','珠海','--destination','桂林','--dep-date','2026-11-18','--back-date','2026-11-23','--total-duration-hour','3','--sort-type','4']);
