// B2 验收脚本：只做一件事 —— 把后端探针接口真实调一遍，看拿到的是不是真数据。
// 用 Node 发请求而不是 curl：中文参数由 URL API 正确百分号编码，不受 shell 编码影响。

const BASE = 'http://127.0.0.1:8003';

async function probe(path, params) {
  const url = new URL(BASE + path);
  for (const [k, v] of Object.entries(params)) url.searchParams.set(k, v);
  const t0 = Date.now();
  const res = await fetch(url);
  const text = await res.text();
  let body;
  try { body = JSON.parse(text); } catch { body = { RAW: text }; }
  return { ms: Date.now() - t0, http: res.status, body };
}

const line = (s) => console.log(s);

(async () => {
  line('======== 1. 健康检查 ========');
  const h = await probe('/api/health', {});
  line(`  HTTP ${h.http}  ${h.ms}ms  code=${h.body.code}`);

  line('');
  line('======== 2. 景点探针：深圳 / 动物园 ========');
  const p = await probe('/api/flyai/probe/poi', { city: '深圳', category: '动物园' });
  line(`  HTTP ${p.http}  ${p.ms}ms  code=${p.body.code}`);
  line(`  真实命令 = ${JSON.stringify(p.body.data.command)}`);
  const env = p.body.data.result;
  line(`  飞猪信封 = data:${env.data ? '有' : 'null'}  message:${env.data?.message}  status:${env.data?.status}`);
  const list = env.data?.itemList ?? [];
  line(`  返回条数 = ${list.length}`);
  list.slice(0, 4).forEach((it, i) => {
    line(`   ${i + 1}. ${it.name}  | 分类:${it.category}  | 票价:${it.ticketInfo ? it.ticketInfo.price + ' ' + it.ticketInfo.ticketName : '无票'}  | itemId:${it.ticketInfo?.itemId ?? '-'}`);
  });

  line('');
  line('======== 3. 酒店探针：深圳（无日期）========');
  const ho = await probe('/api/flyai/probe/hotel', { city: '深圳', poiName: '长隆' });
  line(`  HTTP ${ho.http}  ${ho.ms}ms  code=${ho.body.code}`);
  const hlist = ho.body.data.result.data?.itemList ?? [];
  line(`  返回条数 = ${hlist.length}`);
  hlist.slice(0, 4).forEach((it, i) => {
    line(`   ${i + 1}. ${it.name}  | 价:${it.price}  | 星级:[${it.star}]`);
  });

  line('');
  line('======== 4. 空结果：一个不存在的地名 ========');
  const e = await probe('/api/flyai/probe/poi', { city: '深圳', keyword: '阿凡达悬浮山乐园' });
  line(`  HTTP ${e.http}  ${e.ms}ms  code=${e.body.code}`);
  line(`  飞猪信封 = ${JSON.stringify(e.body.data.result)}`);
  line('  ↑ 注意：data 是 null，但 status 仍然是 0 —— 不能靠状态码判断"有没有数据"');

  line('');
  line('======== 5. 参数确实被切碎了吗（带空格的城市名）========');
  const s = await probe('/api/flyai/probe/poi', { city: '深圳 南山', category: '动物园' });
  line(`  HTTP ${s.http}  ${s.ms}ms  code=${s.body.code}`);
  line(`  真实命令 = ${JSON.stringify(s.body.data.command)}`);
  line(`  ↑ 看命令数组里 "深圳 南山" 是不是**一个完整的元素** —— 是，就说明没经过 shell 切割`);
})().catch((e) => {
  line('脚本失败：' + e.message);
  process.exit(1);
});
