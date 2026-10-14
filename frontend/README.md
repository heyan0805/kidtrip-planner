# 童行 · 带娃旅游规划器 —— 前端

> **和 AI 聊几句，它去飞猪查真实的景点 / 酒店 / 车次，然后把一份带娃真的能跑完的行程排给你。**

技术栈：**React 19 · TypeScript · Vite 8 · Tailwind 3**
目录：`kidtrip-learn/frontend`（后端在同级 `../backend`，Spring Boot，端口 **8003**）

---

## 1. 这是什么

带娃出门，费时间的不是"去哪儿"，是**排行程**：每天去哪儿、几点去、住哪、怎么去、回程车几点的、孩子会不会累。

这个前端就是那条流程的界面：

| 你做的 | 它做的 |
|---|---|
| 用大白话说计划：「想带孩子去广州玩 5 天，7 岁，想看动物，慢节奏」 | 一轮轮把信息问清楚，右侧画像卡逐格填上 |
| 点「生成行程」 | 去飞猪 FlyAI 查真实景点 / 酒店 / 城际车次 → 让模型排期 → 逐天行程 |
| 看、改 | 每个点都能「换一个」；酒店离当天的点超 15 公里会写进「注意事项」 |

**数据边界**：景点、酒店、火车票**全部来自飞猪 FlyAI 接口**，不是模型编的 —— 名称、价格、地址、购票链接、经纬度都是真实返回值。模型只负责两件事：**从候选里挑**，以及**写行程的编排和提示语**。

**它不做什么**（免得误会）：

- ❌ 不接第三方攻略（小红书等**没有接入**，产品的数据源就是飞猪）
- ❌ 不下单、不支付（购票链接跳飞猪）
- ❌ 不保存历史行程（刷新即失，后端没有持久化）

---

## 2. 快速开始

### 2.1 前置条件

- **Node ≥ 20.19 或 ≥ 22.12** —— 这是 Vite 8 的硬要求（`node_modules/vite/package.json` 的 `engines`），版本低了 `npm run dev` 直接起不来。
- **后端跑在 8003**。不想起后端也行，见 2.3 —— 用 Mock 模式看界面。

### 2.2 三步跑起来

```bash
# ① 装依赖
cd frontend
npm install

# ② 起前端
npm run dev                 # → http://localhost:5173
```

前端还需要后端在 8003 上（**另一个终端**）：

```bash
cd ../backend
export DEEPSEEK_API_KEY=sk-xxxx        # Windows PowerShell: $env:DEEPSEEK_API_KEY="sk-xxxx"
mvn spring-boot:run                    # → http://localhost:8003
```

打开 http://localhost:5173，说一句「想带孩子去珠海玩 3 天，孩子 6 岁」，然后点右侧的「生成行程」。

### 2.3 后端没起来 / 不想等那几十秒

前端支持**降级到假数据**，后端不在也能把界面走一遍：

```bash
# macOS / Linux / Git Bash
VITE_USE_MOCK=true npm run dev

# Windows PowerShell
$env:VITE_USE_MOCK="true"; npm run dev
```

界面上会出现**黄色横幅**「正在用假数据」，且假行程里的名字都带「（假数据）」。这是刻意做的，原因见 §6。

> 更干净的做法：在 `frontend/` 下新建 `.env.local` 写 `VITE_USE_MOCK=true`。
> `.env.local` 会覆盖 `.env.development`，且已在 `.gitignore` 里，不会误提交。

### 2.4 端口

| | 端口 | 说明 |
|---|---|---|
| 前端 dev | **5173** | `vite.config.ts` 里写死 |
| 后端 | **8003** | 前端通过 `/api` 代理过去 |

⚠️ 同一工作区里还有一个**参照工程**用 8002 —— 两个工程可以同时跑，互不抢端口。看到 8002 别以为是这个。

---

## 3. 怎么用

1. **说计划**。一次说完也行，分几轮也行。每轮它会把这句话里的信息抽出来填进右侧画像卡。
2. **看画像卡**。8 个格子（城市、天数、孩子年龄、偏好、节奏…），没确定的显示「待确认」。**至少要有城市**才能生成。
3. **点「生成行程」**。这一步要 **25~60 秒**，因为它是真的在查飞猪、再让模型排期 —— 不是假装的进度条。
4. **看结果**：
   - 顶部：行程摘要 + 溯源条（查了几轮飞猪 / 命中 / 可用 / 耗时）
   - 黄色「**注意事项**」：排程冲突、酒店离当天的点太远，都会在这里用一句人话告诉你
   - 每天一张卡：时间点 + 城际/市内交通 + 今晚住哪
   - 每个点右边的「**换一个**」：展开按距离排序的备选清单（**换完不会重新校验行程**，界面上有这行字）
   - 底部「展开溯源」：真实发出的查询命令 + 被筛掉的候选
5. **「重新开始」** 清空重来。

---

## 4. 命令

| 命令 | 作用 |
|---|---|
| `npm run dev` | 起 dev server（5173），带 `/api` → 8003 的代理 |
| `npm run build` | `tsc -b && vite build` → 产物在 `dist/` |
| `npm run preview` | 本地预览 `dist/` 产物 |
| `npm run lint` | ESLint（flat config） |

⚠️ **想查类型就跑 `npm run build`，别跑 `npx tsc --noEmit`。** 根 `tsconfig.json` 是"解决方案文件"（`"files": []` + references），在根目录执行 tsc 是**空转**，不会检查任何文件。

---

## 5. 环境变量

只有 `VITE_` 开头的变量会被注入到前端代码里。配置文件是 `.env.development` / `.env.production`。

| 变量 | dev | prod | 作用 |
|---|---|---|---|
| `VITE_API_BASE` | `/api` | `/api` | 接口前缀。**代码里只写相对路径**（`/plan/chat`），换后端地址只改这里 |
| `VITE_PROXY_TARGET` | `http://localhost:8003` | 不用 | 只被 vite dev server 读，决定 `/api` 转发到哪 |
| `VITE_USE_MOCK` | `auto` | **必须 `false`** | `auto` 只在**连不上**时降级 / `true` 强制假数据 / `false` 只用真后端 |

要临时改，**不要动 `.env.development`**（那是提交进仓库的约定），在同目录新建 `.env.local` 覆盖即可。

---

## 6. 为什么「用假数据」要做得这么显眼

因为**静默降级会造成最难查的一类故障**：后端挂了的那一瞬间，用户看到的不是报错，而是**一份看起来完全正常的假行程** —— 他会照着去订酒店。

所以这个工程对 Mock 加了三道限制：

1. `auto` 模式的判据是"**连不上**"，**不包括"超时"**。`/generate` 正常就要 25~60 秒，如果超时也算"连不上"，那正常生成会被误判成后端挂了，然后静默给假数据。（这是修过的一个真实 bug）
2. 降级会在界面上留**黄色横幅**，并把「正在用假数据」写进状态。
3. 假数据**长得像假的** —— 名字里带「（假数据）」，哪怕横幅没注意到也不会被误认为真结果。

一条通用的规矩：**任何"悄悄地换了数据源"的行为，都必须留下痕迹。**

---

## 7. 目录结构（速览）

```
frontend/
├─ index.html                Vite 入口，唯一挂载点 <div id="root">
├─ vite.config.ts            dev server + /api 代理（全工程唯一一处转发配置）
├─ .env.development          开发变量（提交）/ .env.production 生产变量
├─ tailwind.config.js        content 扫描范围
└─ src/
   ├─ main.tsx         10 行  挂载 React
   ├─ App.tsx           7 行  只有一行：<TravelPlanner />
   ├─ index.css         2 行  三行 @tailwind 指令
   ├─ types/
   │  ├─ flyai.ts     131 行  飞猪接口的原始形状（第三方形状）
   │  └─ plan.ts      454 行  我们自己的契约（前后端逐字一致）
   ├─ api/
   │  ├─ http.ts      240 行  唯一 HTTP 出口（协议 / 超时 / 错误分类 / Mock 降级）
   │  └─ plan.ts      247 行  两个业务接口 + 各自的假数据
   ├─ hooks/
   │  └─ usePlanChat.ts 349 行  对话状态机（前端**全部**业务状态在这）
   └─ pages/
      └─ TravelPlanner/index.tsx  702 行  唯一页面 + 5 个局部组件 + 8 个纯函数
```

9 个源文件、2142 行。三条一句话规矩：

- **`types/` 分两个文件**：`flyai.ts` 因飞猪变，`plan.ts` 因我们变 —— 分开之后飞猪改字段只弄坏一个文件。
- **没有 `components/` 目录**：5 个子组件都是唯一页面的局部函数，换个页面就没意义。**出现第二个页面再搬。**
- **没有 `fetch` 在组件里**：全 `src/` 只有一处 `fetch`（`api/http.ts:188`），其余全部走 `request<T>()`。

---

## 8. 技术选型：为什么没有路由库、没有状态库

**没有路由。** 依赖里只有 `react` + `react-dom`，源码里零处 `BrowserRouter / useNavigate / <Route`。界面变化靠**四个条件渲染**实现（行程区块的有无、Mock 横幅的有无、按钮禁用、清单展开）。这条流程是单向一次性的：没有多页跳转、没有可分享 URL、没有跨页共享数据。

**没有状态库**（redux / zustand / react-query 一个都没装）。原因不是"还不需要"，是**上了会更差**：共享的状态只有一个 hook 的返回值，组件树只有 3 层；只有 2 个接口，都是用户点击触发的一次性调用，没有缓存语义。

真正有意思的是三个手写机制，都在 `hooks/usePlanChat.ts`：

1. **镜像 ref** —— 异步流程里读状态会拿到过期闭包，所以读走 ref、写走 `commitXxx`。
2. **并发闸门 `inFlight`** —— 有请求在飞时"忽略"新点击（不是排队）：把连着说的两句话拆成并发请求，模型会拿到不完整历史、两轮回复互相矛盾。
3. **稀疏覆盖 `swaps`** —— 用户点「换一个」**不改** `plan.days`，只往 `Record<"day:index", SpotOption>` 塞一条，渲染时才叠加。于是撤销＝删一个键，重新生成＝换一份 plan + 清空。

> 这三段都有完整推导（含"为什么这样而不是那样"），见 `ARCHITECTURE.md` 第 3 节。

---

## 9. 想深入看代码

本文件是**入口**：这是什么、怎么跑、怎么用。
`ARCHITECTURE.md`（同目录）是**代码说明**：目录分层的依据、路由/状态/API 的完整推导、一次生成的数据流、建议的读代码顺序、与后端契约的对应关系、缺口清单。

建议从这个顺序读：`types/plan.ts`（只看前 130 行）→ `api/http.ts` → `api/plan.ts` → `hooks/usePlanChat.ts`（最值得花时间）→ `pages/TravelPlanner/index.tsx`。

---

## 10. 测试与验收

- **单元测试：0 个**（前端没装测试框架）。
- **但有浏览器验收脚本**，在 `../_probe/`，用 Playwright 真的开一个浏览器点：

| 脚本 | 验什么 |
|---|---|
| `verify-l1-ui.mjs` | 主链路：说需求 → 生成 → 界面上出现行程 |
| `verify-swap-ui.mjs` | 「换一个」：能不能换、换完有没有被喂一句编造的话 |
| `verify-inbound-ui.mjs` | 往返车次的时间窗口有没有落到界面上 |
| `verify-mock-ui.mjs` | **降级路**：把后端真停掉，界面还能不能打开、像不像"降级" |
| `verify-b5-ui.mjs` | 最早的对话链路 |

跑法（截图落在 `../_probe/shots/`）：

```bash
# ① dev server 要显式监听 IPv4 —— 探针打的是 127.0.0.1
#    Vite 默认 host 是 localhost，在 Windows 上会解析成 ::1（只监听 IPv6），
#    于是 127.0.0.1:5173 连不上。浏览器没事（它会回落），探针不会。
npm run dev -- --host 127.0.0.1

# ② 另开一个终端
cd ../_probe
APP_URL=http://127.0.0.1:5173/ node verify-l1-ui.mjs
```

> 探针依赖 **Playwright**，它不在前端的 `package.json` 里（属于"仓库外的验证工具"）。
> 若 `node` 找不到它，用 `NODE_PATH` 指向装有 Playwright 的 `node_modules` 目录。

---

## 11. 已知问题 / 待办

| # | 事实 | 影响 |
|---|---|---|
| 1 | **0 个单元测试** | 改了 `usePlanChat` 里的三个机制（镜像 ref / 并发闸门 / 稀疏覆盖），没有任何自动化能拦住回归 |
| 2 | `index.html` 的 `<title>` 还是 `frontend` | 浏览器标签页显示"frontend" |
| 3 | `pages/TravelPlanner/index.tsx` **单文件 702 行** | 现在还能读；再长下去，`DayCard` / `SpotRow` / `TransferLine` 是最先该拆出去的三个 |
| 4 | 没有路径别名（没有 `@/`） | 全是相对路径（`'../../hooks/usePlanChat'`）。现在只有 3 层，不值得为它改两处配置 |
| 5 | `types/plan.ts` 里 `PlanRules` / `DEFAULT_PLAN_RULES` 前端从不读取 | 后端已经真正在用 `maxDistanceKm` 了，**前端这份是过期的，改规则别信它**，以 `../_probe/prerun.mjs` 打出的真实 JSON 为准 |
| 6 | `/generate` 期间只有一句「正在生成（要几十秒）」 | 25~60 秒的等待没有任何分步反馈，是全流程体验最差的一段 |
