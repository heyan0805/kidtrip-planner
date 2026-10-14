// 核对飞猪信封的真实形状：成功 / 空结果 两种情况下，message、status 到底在哪一层。
const BASE = 'http://127.0.0.1:8003';

async function env(params) {
  const url = new URL(BASE + '/api/flyai/probe/poi');
  for (const [k, v] of Object.entries(params)) url.searchParams.set(k, v);
  const j = await (await fetch(url)).json();
  return j.data.result;
}

function dump(label, e) {
  console.log(`--- ${label} ---`);
  console.log('  信封顶层 keys  :', JSON.stringify(Object.keys(e)));
  console.log('  顶层 message   :', JSON.stringify(e.message));
  console.log('  顶层 status    :', JSON.stringify(e.status));
  if (e.data) {
    console.log('  data 内 keys   :', JSON.stringify(Object.keys(e.data)));
    console.log('  data.message   :', JSON.stringify(e.data.message));
    console.log('  data.status    :', JSON.stringify(e.data.status));
    console.log('  itemList 长度  :', (e.data.itemList || []).length);
  } else {
    console.log('  data           : null');
  }
  console.log();
}

(async () => {
  dump('成功（深圳 / 动物园）', await env({ city: '深圳', category: '动物园' }));
  dump('空结果（虚构景点）', await env({ city: '深圳', keyword: '阿凡达悬浮山乐园' }));
})();
