import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv } from 'vite'

/**
 * 关键改动（B5）：加 dev 代理。
 *
 * 为什么不直接在前端写后端地址（http://localhost:8003/api）：
 *   ① 会撞跨域。前端跑在 5173、后端在 8003，属于不同源，浏览器会拦下来，
 *      于是你又要去后端加 @CrossOrigin —— 那是**把生产的配置污染成了开发的需要**；
 *   ② 生产环境前后端同源（nginx 把 /api 反代到后端），根本不需要这个地址，
 *      写在代码里等于给生产塞了一个永远用不上的常量；
 *   ③ 更实际的原因：**开发时的跨域问题会掩盖真实的调用链**。
 *      走代理之后，浏览器眼里只有 5173 一个源，请求路径 /api/plan/chat
 *      在开发和生产的**长得一模一样** —— 所以你不用"在本地和线上做两套调试"。
 *
 * 代理目标从环境变量读（VITE_PROXY_TARGET），不写死：
 *   换台机器、换端口、把后端跑到同事机器上，只改 .env.development。
 */
export default defineConfig(({ command, mode }) => {
  // loadEnv 的第三个参数是前缀白名单 —— 不加它，读不到 VITE_ 开头的变量
  const env = loadEnv(mode, process.cwd(), 'VITE_')
  const target = env.VITE_PROXY_TARGET

  // ⚠️ 只在 `vite dev` 时检查（command === 'serve'）。
  //    生产构建（vite build）**本来就不需要**这个变量 —— 代理是 dev server 的功能，
  //    线上由 nginx 反代。第一版忘了加这个判断，结果每次 build 都会报一条
  //    "没有读到 VITE_PROXY_TARGET"，把一个正常状态说成了配置缺失。
  //    **一条会在正常情况出现的警告，会让所有警告都失去意义** —— 它会训练人忽略警告栏。
  if (command === 'serve' && !target) {
    console.warn(
      '[vite] 没有读到 VITE_PROXY_TARGET，/api 将不会被转发到后端。' +
      '请检查 .env.development 是否存在。',
    )
  }

  return {
    plugins: [react()],
    server: {
      port: 5173,
      // 前缀不重写：后端真实路径就是 /api/plan/chat，原样转发即可。
      // 一旦这里加了 rewrite，前后端的路径约定就变成"两处拼起来才是对的"——
      // 那是最容易在改接口时漏掉一半的地方。
      proxy: target ? { '/api': { target, changeOrigin: true } } : undefined,
    },
  }
})
