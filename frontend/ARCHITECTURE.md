# 童行 · 带娃旅游规划器 —— 前端代码说明

> 对象：本仓库的 `frontend/`（Vite + React 19 + TypeScript）
> 结论先行：**9 个源文件（3032 行，其中 40% 是注释），零路由、零状态库、零测试框架。
> 整个 `src/` 里只有一处 `fetch`。**

---

## 0. 一句话总览

| 问题 | 答案 |
|---|---|
| 运行时依赖 | **只有两个**：`react` + `react-dom`（`package.json` 的 `dependencies` 就这两行） |
| 有几个页面 | 1 个 |
| 路由 | **没有**。没装 router，源码里零处路由用法 |
| 状态管理 | **没有库**。两层：一个自定义 hook（业务状态）+ 组件内 `useState`（界面状态） |
| HTTP | 一处 `fetch`，在 `src/api/http.ts:197`。其余全部走它包出来的 `request<T>()` |
| 测试 | **0 个**，也没装测试框架 |

这不是"做了简化"，而是这条产品决策的直接结果：整条流程是**单向一次性的**
（对话补齐信息 → 生成一份行程 → 看），没有多页面跳转、没有可分享的 URL、
没有跨页共享的数据。下面每一节都会把"为什么现在这样是对的"和"什么情况下会不够用"一起写。

---

## 1. 目录结构

```
frontend/
├─ index.html                    13 行   Vite 入口，唯一挂载点 <div id="root">
├─ vite.config.ts                46 行   dev server + /api 代理（全工程唯一一处转发配置）
├─ .env.development                      VITE_API_BASE / VITE_PROXY_TARGET / VITE_USE_MOCK
├─ .env.production                       VITE_API_BASE / VITE_USE_MOCK=false
├─ tailwind.config.js                    content 扫描范围（决定哪些 class 不被 purge）
├─ postcss.config.js                     tailwind + autoprefixer
├─ eslint.config.js                      flat config，react-hooks / react-refresh 规则
├─ tsconfig.json                         解决方案文件：只有 references，files 是空数组
├─ tsconfig.app.json                     真正的编译配置（src 用这个）
├─ tsconfig.node.json                    vite.config.ts 用这个（node 环境）
├─ public/favicon.svg                    唯一静态资源
└─ src/
   ├─ main.tsx                   10 行   挂载 React（StrictMode + createRoot）
   ├─ App.tsx                     7 行   只有一行：<TravelPlanner />
   ├─ index.css                   2 行   三行 @tailwind 指令
   ├─ types/
   │  ├─ flyai.ts                131 行  飞猪 FlyAI 的**原始返回形状**（第三方形状）
   │  └─ plan.ts                 559 行  我们自己的契约（前后端共享的领域类型）
   ├─ api/
   │  ├─ http.ts                 249 行  唯一 HTTP 出口（协议、超时、Mock 降级）
   │  └─ plan.ts                 431 行  两个业务接口 + 进度轮询 + 各自的假数据
   ├─ hooks/
   │  └─ usePlanChat.ts          539 行  对话状态机（前端**全部**业务状态在这里）
   └─ pages/
      └─ TravelPlanner/
         └─ index.tsx            1104 行  唯一页面 + 9 个局部组件 + 10 个纯函数（顶层共 25 个声明）
```

### 1.1 分层依据：按"变化的原因"切，不按"文件类型"切

这是整个目录结构唯一一条原则。三个具体体现：

**① `types/` 为什么是两个文件，而不是一个 `types.ts`**

| 文件 | 变化的原因 | 谁改字段 |
|---|---|---|
| `types/flyai.ts` | 飞猪改了接口 | 外部，我们只能跟进 |
| `types/plan.ts` | 我们自己改了契约 | 我们，要同时改后端 |

分开之后，飞猪改字段只会弄坏 `flyai.ts` 一个文件，不会污染我们自己的契约。
反过来，你改自己的契约时，`flyai.ts` 一行都不用看。

**② `api/` 为什么也是两个文件**

`http.ts` 管的是**协议**（`{code, message, data}` 怎么解读、超时多长、什么时候降级到假数据）；
`plan.ts` 管的是**业务接口**（路径、超时、假数据）。
一句话：`http.ts` 因为你换后端框架而变，`plan.ts` 因为你改接口而变。

**③ 没有 `components/` 目录**

全部 9 个局部组件（`ProfileCard` / `GeneratingPanel` / `StepLine` / `PlanView` / `DayCard` /
`SpotRow` / `TransferLine` / `AlternativeLine` / `HotelAlternativeLine`）
都是 `pages/TravelPlanner/index.tsx` 里的**局部函数**，只有 `TravelPlanner` 一个 `export default`。
理由是它们都是"这一页专用"的：`DayCard` 换个页面就没有意义。
**只有当第二个页面需要同一个组件时，才把它搬到 `components/`。** 现在没有第二个页面。

### 1.2 每条分层的一条铁规矩

| 层 | 规矩 | 违反的后果 |
|---|---|---|
| `pages/` | 不许写 `fetch`，也不许拼 URL | 接口地址散落各处，改后端路径时漏掉一处 |
| `hooks/` | 只放"能被两个以上组件看到"的状态 | 界面状态混进 hook，重渲染范围失控 |
| `api/` | 路径只写相对路径（`/plan/chat`） | 前缀 `/api` 本来是环境的事，写进来就成了代码的事 |
| `types/` | 字段名与后端 JSON **逐字一致**（camelCase） | `childAge` 对不上 `child_age` 时**不报错**，只是永远是 `undefined` |

---

## 2. 路由配置

### 2.1 结论：没有路由，一个都没有

三条可验证的证据：

```bash
# ① 依赖里没有路由库（依赖只有 react 和 react-dom）
grep -iE "router" package.json        # → 无结果

# ② 源码里零处路由用法
grep -rniE "BrowserRouter|createBrowserRouter|useNavigate|<Route" src/   # → 无结果

# ③ 唯一的"视图切换"是条件渲染
```

`main.tsx` → `App.tsx` → `TravelPlanner`，链路到此结束：

```tsx
// src/App.tsx 全文（7 行）
import TravelPlanner from './pages/TravelPlanner'

function App() {
  return <TravelPlanner />
}

export default App
```

### 2.2 那"界面在变"是靠什么实现的

靠四个**状态驱动的条件渲染**，都在同一页里：

| 位置 | 触发条件 | 效果 |
|---|---|---|
| `index.tsx:144` | `chat.plan` 从 `null` 变成一个对象 | 行程区块整体出现 |
| `index.tsx:53` | `chat.mockNotice` 非空 | 顶部黄色"正在用假数据"横幅出现 |
| `index.tsx:142` | `chat.canGenerate` 为假（= `missingFields` 非空） | "生成行程"按钮禁用 + 列出还差哪几项 |
| `index.tsx:479` | `SpotRow` 内的 `open` state | 该点的「换一个」备选清单展开/收起 |

这四件事里有三个是"**同一页内某块内容的有无**"，第四个（`open`）是纯界面状态。
它们的共同点是：**不需要 URL、不需要后退、不需要刷新后还原。**

### 2.3 如果以后要加路由

场景：加"行程历史"页、或让用户能分享某一份行程的链接。做法与三个坑：

1. `main.tsx` 外面包 `<BrowserRouter>`，`App.tsx` 里改成 `<Routes>`。改动很小。
2. **生产环境的 nginx 必须配 `try_files $uri /index.html`** ——
   否则用户刷新 `/history` 会 404（服务器上根本没有这个文件）。
   dev 环境 Vite 自带这个兜底，所以**这个坑只会在上线后出现**。
3. **不要把"某一份行程"做成路由参数**（如 `/plan/:id`）。
   行程不是后端可复现的资源 —— 没有 id、每次生成都不一样（模型温度 0.3 + 实时价格）。
   真要分享，得先把行程存到后端，那是另一个功能。
   ⚠️ 现在 `vite.config.ts` 的代理只代理 `/api`，前端路由不受影响。

---

## 3. 状态管理方式

### 3.1 结论：两层手写状态，没有库

```bash
grep -iE "redux|zustand|jotai|recoil|mobx|react-query|swr" package.json   # → 无结果
```

**为什么这里不该上库**（不是"还不需要"，是"上了会更差"）：

| 常见理由 | 在这个工程里不成立 |
|---|---|
| "跨组件共享状态不方便" | 共享的只有**一个** hook 的返回值，组件树只有 3 层，props 传递一共 4 处 |
| "请求缓存/重试/失效要自己写" | 只有 2 个接口，都是**用户主动点击**触发的一次性调用，没有缓存语义 |
| "状态多了会乱" | 业务状态一共 8 个 `useState`；乱的原因从来不是数量，是**状态之间没有单一归属** |

最后一条是重点：这个工程的解决办法不是引入 reducer，而是**给每个状态指定唯一的属主**（见 3.4 的稀疏覆盖）。

### 3.2 两层状态，分界线很明确

| 层 | 在哪 | 放什么 | 例子 |
|---|---|---|---|
| **业务状态** | `hooks/usePlanChat.ts` | 跨组件可见、或跨时间存活 | `messages` / `params` / `plan` / `swaps` / `sending` / `generating` / `error` / `mockNotice` |
| **界面状态** | `pages/TravelPlanner/index.tsx` | 只影响本组件渲染 | `draft`（输入框内容）、`open`（清单展开）、`listRef`（滚动容器） |

规矩一句话：**能被两个组件看见的状态才进 hook；只在一个人手里的，留在 `useState`。**
判据是"谁需要知道它"，不是"它重不重要"。

### 3.3 `usePlanChat` 内部：9 个 state + 6 个 ref

| 名字 | 类型 | 作用 |
|---|---|---|
| `messages` | `ChatBubble[]` | 对话历史（给人看，越攒越长） |
| `params` | `TravelParams` | 已提取的画像（给机器用，只有 8 个字段） |
| `plan` | `TravelPlan \| null` | 生成结果。`null` = 还没生成 |
| `sending` / `generating` | `boolean` | 两个阶段各自的"正在忙"标志 |
| `error` | `string \| null` | 一句能给用户看的话 |
| `mockNotice` | `string \| null` | "正在用假数据"的提示 |
| `swaps` | `SpotEdits` | 用户手动换过的点（稀疏覆盖，见 3.4③） |
| `progress` | `PlanProgress \| null` | 生成到第几步了（每秒轮询拿回来的，见第 4 节「进度接口」） |
| `messagesRef` / `paramsRef` | `useRef` | 上面两个 state 的**镜像**（见 3.4①） |
| `inFlight` | `useRef<boolean>` | **并发闸门**（见 3.4②） |
| `abortRef` | `useRef<AbortController>` | 卸载/重置时取消在飞的请求 |
| `pollTimer` / `pollBusy` | `useRef` | 进度轮询的定时器与"上一拍还没回来"的闸门（见第 4 节「进度接口」） |

对外返回 9 个状态 + 2 个派生值（`canGenerate` / `missingFields`）+ 5 个方法
（`sendRound` / `generate` / `swapSpot` / `restoreSpot` / `reset`），共 16 个键 ——
和 `UsePlanChat` 接口声明逐一对得上。

⚠️ `canGenerate` 的判据是 `REQUIRED_FIELDS`（五项：出发地 / 目的地 / 天数 / 出发日期 / 孩子年龄），
**不是"知道城市就行"**。它原来写的是 `Boolean(params.city?.trim())`，
后果是"天数 / 出发日期 / 孩子年龄"还显示「待确认」的时候就能点，
而后端 `orDefaults()` 会把 days 静默补成 3 —— 用户说 9 天，出来一份 3 天的行程。
**默认值是对的兜底，但它绝不能被当成"用户已经说过了"。**

`REQUIRED_FIELDS` 从 hook 里导出，画像卡那边也用它标"哪个格子必须填"。
两处共用一个判据来源：分开写一定会漂移，而漂移的症状是"按钮亮着、界面上却有个必填格子还空着"，
两个地方都不报错，只是互相矛盾。

**为什么 `messages` 和 `params` 必须是两份状态、不能合成一份**：
用户聊了 20 轮之后，早期那句话可能已经被后端从历史里裁掉了，
但 `params.city` 依然是"珠海"。**把事实从历史里提炼出来单独存，是让长对话不退化的关键一步。**

### 3.4 这份代码真正的三个难点（读代码时最值得花时间的地方）

#### ① 镜像 ref —— 解决"过期闭包"

```ts
const messagesRef = useRef<ChatBubble[]>([])
const commitMessages = useCallback((next: ChatBubble[]) => {
  messagesRef.current = next      // ← 写 ref
  setMessages(next)               // ← 同时写 state
}, [])
```

**现象**：函数组件每次渲染都会新建一整套闭包。`sendRound` 在 `await` 之后拿到的
`messages`，是**它被创建那一刻**的那一份 —— 用户在等的十几秒里如果又发了消息，
用它去拼下一次请求就会**把新消息覆盖掉**。

**规矩**：异步流程里**读**状态一律走 `ref`，**写**状态一律走 `commitXxx`（两者一起变）。
违反的后果是"界面是新的、请求是旧的"，而且不报错。

#### ② 并发闸门 —— `inFlight`

```ts
const inFlight = useRef(false)
...
if (inFlight.current) return          // 已经有请求在飞 → 忽略这次点击
inFlight.current = true
try { ... } finally { inFlight.current = false }
```

用 `ref` 而不是 `useState` 的两个理由：① 它不需要触发重渲染；
② 它必须**立刻**可见（`useState` 的更新是异步的，同一帧里连点两下会两个都放行）。

**是"忽略"不是"排队"**：对话是有顺序的语义，把连着说的两句话拆成两个并发请求，
模型会分别拿到不完整的历史，第二轮回复可能和第一轮矛盾。
**宁可他等一下，也不要给他一份自相矛盾的对话。**
界面侧靠 `disabled` 让"等一下"这件事可见（而不是点了没反应）。

#### ③ 稀疏覆盖 —— `swaps`

```ts
export type SpotEdits = Record<string, SpotOption>   // 键是 `${day}:${index}`
```

用户点「换一个」，**不是**去改 `plan.days`，而是往这个表里塞一条覆盖：

```ts
const days = applySwaps(plan.days, swaps)   // 渲染时才叠加
```

| | 直接改 `plan.days` | 稀疏覆盖（现在的做法） |
|---|---|---|
| 后端给的事实 | 被覆盖掉，原始值没了 | **一个字节都不改** |
| 撤销一个替换 | 需要另存快照 | **删掉一个键**即可 |
| 重新生成 | 分不清哪些是"后端的"哪些是"用户的" | 换一份 plan + 清空 swaps，两件事独立 |

这条背后的原则和 `types/plan.ts` 顶上那句是同一句：
**谁产生的状态，谁负责它。后端产 fact，用户产 edit，界面只负责叠加。**

两个刻意的细节：
- **`time` 保留**：用户换的是"去哪儿"，不是"几点去"。把时间一起洗掉，他会发现节奏被顺手抹平了。
- **`tips` 清空**：模型的建议是针对"某个点在某个行程里的位置"写的。
  「长隆野生动物世界很大，建议先坐小火车」搬到"广州动物园"上，
  就是一句**读起来完全正常、实际纯属编造**的话 —— 这正是这个产品最该怕的错。
  所以宁可空着，并在界面上写明「换上的备选没有游玩建议」。

---

## 4. API 调用方式

### 4.1 调用链：组件永远不碰 `fetch`

```
组件 (index.tsx)
  └─ usePlanChat() 里的 chat.sendRound / chat.generate
       └─ api/plan.ts   chatPlan() / generatePlan()      ← 路径、超时、假数据
            └─ api/http.ts   request<T>()                 ← 协议、超时、Mock 降级、错误分类
                 └─ fetch()                               ← 全工程唯一一处（http.ts:197）
```

### 4.2 `api/http.ts` —— 唯一出口

**它负责且只负责四件事**：拼前缀并发出、把 `{code,message,data}` 翻译成"返回 data 或抛 `ApiError`"、超时、按需降级到假数据。
它**不**负责业务判断（"哪些字段齐了才能生成"），那是调用方的事。

**为什么必须集中**：后端约定 HTTP 状态码**恒为 200**，业务失败用 `code !== 0` 表达。
所以**不能靠 `res.ok` 判断成败** —— 每个调用点自己写一遍这段判断，就等于把同一条协议抄 N 遍，抄错一处就是"失败了却当成成功"。

#### 错误分类：`ApiError.kind` 五值

| kind | 含义 | 调用方能做什么 |
|---|---|---|
| `business` | 后端明确拒绝（`code !== 0`） | `message` 直接给用户看，重试没用 |
| `network` | 根本没连上 | **唯一一种可以降级到 Mock 的失败** |
| `timeout` | 连上了但等太久 | ⚠️ 和 `network` 是**两件事**，见下 |
| `badResponse` | 连上了、也回了，但内容不对（非 200 / 不是 JSON） | 多半是代理配错了 |
| `aborted` | 调用方自己取消（组件卸载） | 不是错误，不该弹提示 |

**为什么 `timeout` 和 `network` 必须分开**：这是修过的一个真实 bug。
`/generate` 本来就要 25~60 秒，之前超时设 60 秒，于是**正常生成经常超时**，
而超时被当成"后端连不上"，在 `auto` 模式下**静默降级到假数据** ——
用户等了 60 秒，拿到一份假行程，还以为是真的。
修法两层：① 超时放到 180 秒；② **超时不再等同于连不上**。只做 ① 的话，后端再慢一点同样的事还会发生。

#### 超时用 `AbortController` 而不是 `Promise.race`

`race` 只是"我们不等了"，请求其实还在发、还占着连接；
`abort` 是**真的告诉浏览器取消掉**它。后端那个接口一次要花几十秒 + 一笔 token 费，
前端放弃等待却让它继续跑，等于在后端留一个没人要的重活。

#### `DEFAULT_TIMEOUT_MS = 180_000` —— 这个数字是算出来的

`/generate` 正常 25~60 秒（查车次 + 规划检索条件 10~20s + 检索 2~10s + 排行程 10~30s）。
2026-10-13 从 120 秒提到 180 秒，因为一次生成又多了**两段城际车次查询**：
单次 CLI 1.3~2.7 秒，但中间要留调用间隔，被限流时退避 15 秒 —— 最坏两段能到 40 秒。
**这个数字跟着后端预算走，后端改了这里要跟着改。**

#### Mock 三态

| `VITE_USE_MOCK` | 行为 |
|---|---|
| `auto`（默认） | 后端**连不上**时才降级（只认 `network`） |
| `true` | 强制假数据 |
| `false` | 只用真后端（**生产必须**） |

降级是**静默发生**的，所以有一件事必须做：把"正在用假数据"告诉用户。
`subscribeMockNotice()` 就是这条通道 —— hook 订阅它、界面把它渲染成顶部黄色横幅。
**任何"悄悄地换了数据源"的行为，都必须留下痕迹。**

### 4.3 `api/plan.ts` —— 三个接口

```ts
chatPlan(req, signal)                 // POST /plan/chat              超时 30 秒（只调一次模型）
generatePlan(req, signal, runId?)     // POST /plan/generate          用默认 180 秒
fetchPlanProgress(runId, signal)      // GET  /plan/progress/{runId}  超时 5 秒，可能返回 null
```

`generatePlan` 里**刻意不写超时**：写一个"看起来更合理"的 60 秒就会复现上面那个 bug。
180 那个数字只在 `http.ts` 里声明一次。

#### 进度接口：一个"旁路接口"，三处刻意和别的接口不一样

| 选择 | 为什么 |
|---|---|
| **不传 `mock`** | `request()` 只在"`network` 失败 **且** 备了 mock"时才降级，不传就是不参与降级。进度是纯观测，问不到就安静算了 —— **一个观测动作不该改变用户对数据源的整体判断**（它要是能触发降级，界面顶上就会挂出一条「正在使用假数据」） |
| **超时 5 秒**（默认 180） | 那个 180 是留给 `/generate` 那种重活的。进度是几十字节的查询，5 秒不回来说明这一次不值得等 —— 反正下一拍 1 秒后就到 |
| **可能返回 `null`** | 后端查不到这个 runId（这趟已收场、或记录过了 5 分钟有效期）。**这不是错误**：轮询的最后一拍总落在生成结束之后，`null` 就是"这趟已经不在场上了"的信号，前端据此安心收工 |

`runId` 走**请求头 `X-Run-Id`**，不进请求体 —— 进度不是这次生成的输入，只是"我想看它怎么跑"。
塞进 body 就等于往那份被后端契约测试和前端类型两头钉着的 `PlanGenerateRequest` 里凭空加一个字段。

**为什么是轮询，不是流式**：这条产品要跑在内网穿透上，而 **Cloudflare 免费隧道不支持 SSE** ——
流式会在最要命的那一刻断掉。轮询在任何代理上都稳，代价只是进度最多晚 1 秒。

**请求体的两条传递规矩**（都写在 `types/plan.ts` 的注释里）：

- `POST /plan/chat` 传 `{ messages, currentParams }`：
  `messages` 是**完整历史**，`currentParams` 是上次响应原样带回来的画像。
  ⚠️ 不要只传"本轮新增" —— 后端会把增量当成完整状态，把之前的城市、天数全丢掉，且不报错。
  ⚠️ 也不要自己塞默认值（比如 `days: 3`）—— 后端会认为"天数已经问过了"，于是再也不问。
- `POST /plan/generate` 只传 `{ params }`：
  **不要自己拼 `retrieval`** —— 检索条件该由后端按画像规划，前端传一份自己猜的会把它顶掉。

**假数据的两条原则**（写在 `api/plan.ts` 里）：
① 假数据必须**长得像假的**（名字里带"（假数据）"），哪怕提示没显示出来也不会被误认为真结果；
② Mock **不做超出能力的事** —— 只做最粗暴的关键词抠取，不去"模拟一个聪明的模型"。

### 4.4 地址与代理

| 环节 | 事实 |
|---|---|
| 代码里写的 | 相对路径 `/plan/chat`（前缀不在代码里） |
| 前缀从哪来 | `import.meta.env.VITE_API_BASE`，缺省 `/api` |
| 开发时谁转发 | `vite.config.ts` 的 `server.proxy`，`/api` → `VITE_PROXY_TARGET` |
| **不加 `rewrite`** | 后端真实路径就是 `/api/plan/chat`，原样转发。加了 rewrite 就变成"两处拼起来才是对的"，改接口时最容易漏掉一半 |
| 生产时谁转发 | nginx 把 `/api` 反代到后端 |
| 为什么不用 `http://localhost:8003/api` | ① 会撞跨域，逼后端加 `@CrossOrigin`（把生产配置污染成开发需要）② 生产前后端同源，这个地址永远用不上 ③ 走代理后 `/api/plan/chat` 在开发和**长得一模一样**，不用做两套调试 |

### 4.5 环境变量

| 变量 | dev | prod | 说明 |
|---|---|---|---|
| `VITE_API_BASE` | `/api` | `/api` | 接口前缀 |
| `VITE_PROXY_TARGET` | `http://localhost:8003` | 不用 | 只被 vite dev server 读 |
| `VITE_USE_MOCK` | `auto` | `false` | 生产必须 `false` |

⚠️ `vite.config.ts` 里读环境变量必须走 `loadEnv(mode, process.cwd(), 'VITE_')` ——
第三个参数是前缀白名单，**不加它读不到 `VITE_` 开头的变量**。
⚠️ 代理缺失的警告只在 `command === 'serve'` 时打：生产构建本来就不需要这个变量，
在 build 时报警会把一个正常状态说成配置缺失，而**一条会在正常情况出现的警告，会让所有警告都失去意义**。

---

## 5. 一次生成的完整数据流

```
用户在输入框打字
   │  index.tsx 的 draft（界面状态）
   ▼
chat.sendRound(text)            usePlanChat
   │  ① 闸门检查 inFlight
   │  ② 把用户这句先落到界面（唯一的即时反馈）
   │  ③ 读 messagesRef / paramsRef（不是 state）
   ▼
chatPlan({ messages, currentParams })     api/plan.ts
   ▼
request<PlanChatData>('/plan/chat')       api/http.ts
   │  AbortController（180s 默认 / 这里 30s）
   │  {code,message,data} → data 或 ApiError
   ▼
fetch → vite proxy → 后端 8003
   ▼
{ reply, params }
   │
   ├─► commitMessages([...ref, 助手回复])   → 界面上多一个气泡
   └─► commitParams(data.params)            → 右侧画像卡某个格子从"待确认"变成实值

──── 用户点「生成行程」 ────────────────────────────────

chat.generate()
   │  setPlan(null) + setSwaps({})（旧行程的手动改动必须跟着旧行程一起消失）
   │  生成一个 runId → 塞进请求头 X-Run-Id，同时 startPolling(runId)
   ▼
generatePlan({ params })  →  request<TravelPlan>('/plan/generate')  →  后端
   │   ↺ 每秒 GET /plan/progress/{runId} → setProgress（五步流水线，见第 4 节）
   ▼
TravelPlan { summary, days[], warnings[], provenance, spotPool[] }
   │
   ├─► PlanView：applySwaps(plan.days, swaps) ← 用户改的在这里才叠上去
   │     ├─ 画像卡之上：summary + "查了 N 轮飞猪 / 命中 / 可用 / 耗时"
   │     ├─ warnings → 黄色「注意事项」
   │     ├─ 每天一张 DayCard：点 + TransferLine + 酒店行
   │     │  （同一段路还有别的走法时，主选下面多一行 AlternativeLine —— 目前只可能是飞机）
   │     ├─ 每个点一个 SpotRow：「换一个」（alternativesFor 每天算一次）
   │     └─ <details> 账本：真实发出的查询命令 + 被筛掉的候选
   └─► spotPool 是「换一个」的候选来源（含已排进日程的点，切片由前端做）
```

---

## 6. 建议的读代码顺序

从**形状**读到**逻辑**，别从页面开始（页面那 1104 行里大半是 JSX 和 `className`，容易劝退）：

1. `src/types/plan.ts` —— **只看前 130 行**（`Message` / `TravelParams` / `PlanChatRequest` / `RetrievalPlan`）。
   先知道"数据长什么样"，后面全是它的搬运和渲染。
2. `src/api/http.ts` —— 全工程的协议地基。看懂 `ApiError.kind` 五值，就懂了所有错误处理。
3. `src/api/plan.ts` —— 只有两个函数，一分钟看完。
4. `src/hooks/usePlanChat.ts` —— **最值得花时间的一站**。3.4 的三个难点都在这里。
5. `src/pages/TravelPlanner/index.tsx` —— 前四步走完再看。
   25 个顶层声明 = **10 个组件**（页面本身 + 9 个局部）+ **10 个纯函数** + 2 个常量 + 3 个局部 `interface`。
   那 10 个纯函数（`missingLabels` / `renderField` / `formatSeconds` / `alternativesFor` /
   `distanceKm` / `centroid` / `formatKm` / `hhmm` / `formatDuration` / `mergeStays`）
   可以单独读，它们不碰任何 React 概念。

---

## 7. 与后端契约的关系（哪些是"镜像"）

`types/plan.ts` 导出了 21 个类型（外加一个常量 `DEFAULT_PLAN_RULES`），但**被应用代码真正 import 的只有 12 个**：

```
被 import 的：Message · TravelParams · PlanChatData · PlanChatRequest · PlanGenerateRequest
              SpotOption · Transfer · TravelDay · TravelPlan · TravelSpot
              PlanProgress · PlanProgressStep

只作为嵌套字段类型出现的：PlanProvenance（TravelPlan.provenance）·
                          RetrievalPlan（PlanProvenance.retrievalPlan）·
                          RejectedCandidate（PlanProvenance.rejected[]）·
                          HotelStay（TravelDay.hotel）· ProgressStepState（PlanProgressStep.state）

前端从不使用的（后端检索层/规则层的镜像）：PoiCandidate · HotelCandidate ·
                                          Candidate · PlanRules · DEFAULT_PLAN_RULES
```

`types/flyai.ts`（131 行）同理：**整个文件只有 `PoiCategory` 被引用一次**，
且只在 `RetrievalPlan.wantedCategories` 这个类型位置上，**运行时不产生任何代码**。

**这不是冗余**，理由和 1.1 是同一个：这些是"改了要前后端一起改"的那份契约。
留在前端是为了**改动时能看见对面**。代价是它们会过期 ——
`DEFAULT_PLAN_RULES.maxDistanceKm` 和 `PlanRules` 现在就是这样：
后端已经真正用起来了，前端这份从没被读过。**下次改规则时以 `_probe/prerun.mjs`
打出来的真实 `PlanRules` JSON 为准，别信这个文件。**

---

## 8. 目前的缺口（诚实清单）

| # | 事实 | 影响 |
|---|---|---|
| 1 | **0 个前端测试，也没装测试框架** | 后端改坏了测试会拦，前端只能靠看。3.4 里那三个难点全部落在没有任何自动化保护的地方 |
| 2 | 根 `tsconfig.json` 是**解决方案文件**（`"files": []` + references） | `npx tsc --noEmit` 在根目录是**空转**。真正的类型检查是 `npm run build` 里的 `tsc -b` |
| 3 | `README.md` 还是 Vite 模板原文 | 看完不知道这是干什么的 |
| 4 | `index.html` 的 `<title>` 还是 `frontend` | 浏览器标签页显示"frontend"（`lang` 已改成 `zh-CN`） |
| 5 | `pages/TravelPlanner/index.tsx` **单文件 1104 行** | 已经**越过了**该拆的线；`DayCard` / `SpotRow` / `TransferLine` / `AlternativeLine` / `HotelAlternativeLine` 是最先该拆出去的几个 |
| 6 | 没有路径别名（没有 `@/`） | 全部相对路径（`'../../hooks/usePlanChat'`）。层次再深就会开始难看，但现在是 3 层，不值得为它改 tsconfig + vite 两处 |
| 7 | `spotPool` 里的点也包含已排进日程的那些 | 这是**故意的**（见 `types/plan.ts` 的三条性质）：用户把 A 换成 B 后 A 要能换回来。切片由前端的 `alternativesFor` 做，**每换一次都要重切** |

---

## 附：一句话记住每个文件

| 文件 | 一句话 |
|---|---|
| `main.tsx` | 把 React 挂到 `#root` |
| `App.tsx` | 只有一个页面，所以只有一行 |
| `types/flyai.ts` | 飞猪长什么样（第三方形状，运行时不参与） |
| `types/plan.ts` | 我们的契约（前后端逐字一致，改一处要改两处） |
| `api/http.ts` | 唯一出口：协议 + 超时 + 错误分类 + Mock 降级 |
| `api/plan.ts` | 三个接口（对话 / 生成 / 进度）+ 前两个各自的假数据 |
| `hooks/usePlanChat.ts` | 前端全部业务状态；镜像 ref、并发闸门、稀疏覆盖都在这 |
| `pages/TravelPlanner/index.tsx` | 唯一页面；9 个局部组件 + 10 个纯函数 |
| `vite.config.ts` | dev server 和 `/api` 代理（唯一一处转发配置） |
