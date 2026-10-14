import { FLYAI_CLI } from './_paths.mjs'
// 验证一件事：B3 计划里的"并发调用"到底能不能把总耗时压下来（会不会被上游限流）。
// 对比：4 次调用「串行」vs「并发」的墙钟时间。
import { execFile } from 'node:child_process';

const NODE = process.env.TEST_NODE || 'node';
const B = FLYAI_CLI;

const CALLS = [
  ['search-poi', '--city-name', '深圳', '--category', '动物园'],
  ['search-poi', '--city-name', '深圳', '--category', '主题乐园'],
  ['search-poi', '--city-name', '深圳', '--category', '水上乐园'],
  ['search-hotel', '--dest-name', '深圳'],
];

function run(args) {
  return new Promise((resolve) => {
    const t0 = Date.now();
    execFile(NODE, [B, ...args], { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 }, (err, stdout) => {
      const ms = Date.now() - t0;
      let n = -1;
      try {
        const j = JSON.parse(stdout);
        n = j.data ? (j.data.itemList || []).length : 0;
      } catch { n = -1; }
      resolve({ ms, n, ok: !err, cmd: args[1] + ' ' + args.slice(2).join(' ') });
    });
  });
}

function report(label, rs, wall) {
  console.log(`${label}`);
  rs.forEach((r) => console.log(`   ${String(r.ms).padStart(5)}ms  条数=${String(r.n).padStart(3)}  ok=${r.ok}  ${r.cmd}`));
  console.log(`   >>> 墙钟合计 ${wall}ms`);
}

const seqStart = Date.now();
const seq = [];
for (const c of CALLS) seq.push(await run(c));
const seqWall = Date.now() - seqStart;
report('【串行】4 次调用', seq, seqWall);

console.log();

const parStart = Date.now();
const par = await Promise.all(CALLS.map(run));
const parWall = Date.now() - parStart;
report('【并发】4 次调用同时发起', par, parWall);

console.log();
console.log(`结论：串行 ${seqWall}ms  ->  并发 ${parWall}ms （缩短 ${seqWall - parWall}ms，约 ${Math.round((1 - parWall / seqWall) * 100)}%）`);
console.log(`      4 次全部拿到数据？ ${par.every((r) => r.n > 0) ? '是（未触发限流）' : '否 —— 有调用失败或返回 0 条，B3 的并发假设需要重新评估'}`);
