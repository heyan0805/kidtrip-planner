import { FLYAI_CLI } from './_paths.mjs'
/**
 * 酒店筛选参数实测 —— 目标只有一个：
 *   把「深圳宽查回来的 10 家全是青旅/日租公寓、没有一家适合带娃」这件事修掉。
 *
 * search-hotel --help 给了 5 个筛选维度（hotel-types / hotel-stars / sort / max-price / hotel-bed-types），
 * 但**哪些真的生效、返回的集合差多少**，只能真跑。这个脚本一次跑完一组对照。
 *
 * 判据不是"条数多少"，而是**候选质量**：
 *   ① 还有多少条是青旅/公寓/客栈这类不适合亲子过夜的（看名字关键词）
 *   ② 星级、评分是不是回到了"像样的酒店"这一档
 *
 * 限流前提：串行 + 每次间隔 ≥8 秒（实测并发会 429，不间隔会 451 风控）。
 * 用法：node hotel-filter-test.mjs
 *      加 TEST_DATE=1 会带上真实入离日期（价格随之变成"你要去的那几天"的价格）
 */

import { execFile } from 'node:child_process';

const NODE = process.env.TEST_NODE || 'node';
const B = FLYAI_CLI;
const GAP = Number(process.env.GAP || 8) * 1000;
const WITH_DATE = process.env.TEST_DATE === '1';
const CITY = '深圳';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function run(args) {
  return new Promise((resolve) => {
    execFile(NODE, [B, ...args], { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 }, (err, stdout, stderr) => {
      let j = null;
      try { j = JSON.parse(stdout); } catch { }
      const code = /HTTP (\d{3})/.exec(stderr || '')?.[1] ?? (err ? 'ERR' : '200');
      resolve({
        ok: !err,
        code,
        list: j?.data?.itemList ?? j?.data?.hotelList ?? j?.data?.list ?? [],
        raw: stdout,
        err: (stderr || '').replace(/\s+/g, ' ').slice(0, 200),
      });
    });
  });
}

/** 名字里带这些词 → 不是带娃要住的那种酒店 */
const BAD = ['公寓', '客栈', '青旅', '青年旅舍', '青年公寓', '日租', '短租', '民宿', '旅馆', '招待所'];
const isBad = (name) => BAD.some((w) => (name || '').includes(w));

const num = (v) => {
  if (v == null) return null;
  const m = String(v).replace(/[,¥\s]/g, '').match(/\d+(\.\d+)?/);
  return m ? Number(m[0]) : null;
};

function analyze(label, r) {
  if (!r.ok) {
    console.log(`\n── ${label}\n   ✗ 上游码=${r.code}  ${r.err}`);
    return;
  }
  const l = r.list;
  if (l.length === 0) {
    console.log(`\n── ${label}\n   ⚠ 0 条（可能参数被忽略或组合太窄）  原始前 200 字: ${r.raw.slice(0, 200)}`);
    return;
  }
  const bad = l.filter((x) => isBad(x.name));
  const stars = l.map((x) => num(x.star ?? x.hotelStar ?? x.starRating)).filter((x) => x != null);
  const ratings = l.map((x) => num(x.rating ?? x.score ?? x.commentScore)).filter((x) => x != null);
  const prices = l.map((x) => num(x.price ?? x.priceYuan ?? x.lowestPrice)).filter((x) => x != null);
  const avg = (a) => (a.length ? (a.reduce((s, x) => s + x, 0) / a.length).toFixed(1) : '-');
  const rng = (a) => (a.length ? `${Math.min(...a)}~${Math.max(...a)}` : '-');

  console.log(`\n── ${label}   ✓ ${l.length} 条`);
  console.log(`   不适合亲子: ${bad.length}/${l.length} 条` + (bad.length ? `（${bad.slice(0, 4).map((x) => x.name).join('、')}…）` : ' ✅'));
  console.log(`   星级 平均${avg(stars)} 范围${rng(stars)}   评分 平均${avg(ratings)}   房价 范围${rng(prices)}`);
  for (const x of l.slice(0, 8)) {
    const st = num(x.star ?? x.hotelStar ?? x.starRating);
    const rt = num(x.rating ?? x.score ?? x.commentScore);
    const pr = num(x.price ?? x.priceYuan ?? x.lowestPrice);
    console.log(`     · ${(x.name || '?').padEnd(28)} ${st ? st + '星' : '   '} 评${rt ?? '-'}  ¥${pr ?? '-'}`);
  }
  if (l.length > 8) console.log(`     …另 ${l.length - 8} 条`);
}

const dateArgs = WITH_DATE
  ? ['--check-in-date', '2026-10-24', '--check-out-date', '2026-10-27']
  : [];
const D = WITH_DATE ? ' +日期' : '';

const CASES = [
  ['A. 基线：只有 dest-name（现状）', ['search-hotel', '--dest-name', CITY, ...dateArgs]],
  ['B. --hotel-types hotel', ['search-hotel', '--dest-name', CITY, '--hotel-types', 'hotel', ...dateArgs]],
  ['C. --hotel-stars 4,5', ['search-hotel', '--dest-name', CITY, '--hotel-stars', '4,5', ...dateArgs]],
  ['D. hotel-types=hotel + stars=4,5', ['search-hotel', '--dest-name', CITY, '--hotel-types', 'hotel', '--hotel-stars', '4,5', ...dateArgs]],
  ['E. sort=rate_desc（按评分排）', ['search-hotel', '--dest-name', CITY, '--sort', 'rate_desc', ...dateArgs]],
  ['F. hotel-types=hotel + max-price 900 + rate_desc', ['search-hotel', '--dest-name', CITY, '--hotel-types', 'hotel', '--max-price', '900', '--sort', 'rate_desc', ...dateArgs]],
];

console.log(`酒店筛选对照${D} —— 城市 ${CITY}，串行间隔 ${GAP / 1000}s，共 ${CASES.length} 次上游调用`);
console.log(`判据：越少"公寓/客栈/青旅/日租"越好（带娃不能住青旅）`);

const cool = Number(process.env.COOLDOWN || 20) * 1000;
console.log(`\n先冷却 ${cool / 1000}s（避开上一轮调用可能留下的限流窗口）…`);
await sleep(cool);

for (let i = 0; i < CASES.length; i++) {
  const [label, args] = CASES[i];
  if (i > 0) await sleep(GAP);
  analyze(label + D, await run(args));
}

console.log('\n完成。');
