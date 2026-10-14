# kidtrip-backend · 童行后端

「童行 · 带娃旅游规划器」的后端。**一句话职责：把"用户的模糊出行意愿"变成"一份可执行、可追溯的行程"**，
中间所有的事实（景点、酒店、城际车次、经纬度）都来自飞猪，一格都不许模型自己编。

- Spring Boot **4.1.1** · Java **21** · LangChain4j **1.18.1** · DeepSeek（`deepseek-chat`）
- 端口 **8003**（参照工程是 8002，两个可以同时跑）
- 60 个源文件 / 7121 行 · 19 个测试类 / **222** 个用例

> 前端说明见 [`../frontend/README.md`](../frontend/README.md)；
> 想先看整体架构和产品原则，读仓库根的 [`../README.md`](../README.md)。

---

## 1. 它在这条链路上的位置

```
用户对话 ──► 画像(params)
                │
                ▼
       ┌────────────────────┐
       │  检索层 retrieval    │  调飞猪 CLI 拿真实候选（景点/酒店/城际车次）
       │  限流 + 缓存         │  ← 坐标、价格、地址、车次时刻，都是这一层拿到的
       └─────────┬──────────┘
                 ▼
       ┌────────────────────┐
       │  候选层 candidate    │  规范化 + 去重
       │  规则层 rule         │  白名单筛选（价格上限、类别、时长上限）
       └─────────┬──────────┘
                 ▼
       ┌────────────────────┐
       │  大模型 llm          │  模型**只能从候选里挑编号**，不能自己造名字
       │  三个 Composer       │  名称/价格/地址/链接/坐标由后端按编号回填
       └─────────┬──────────┘
                 ▼
       ┌────────────────────┐
       │  编排层 plan         │  装配 + 兜底校验（首末日冲突、距离跨度、车次时段）
       │                    │  冲突不拦行程，只写进 warnings 让界面说人话
       └─────────┬──────────┘
                 ▼
              行程(TravelPlan) + 注意事项(warnings) + 备选池(spotPool)
```

**核心不变量：模型负责措辞，代码负责事实。**
"编一个不存在的景点"在这套结构里做不到 —— 模型输出的是 `C3` 这样的编号，
名称、票价、地址、购票链接、经纬度全部由后端按编号查表回填。

---

## 2. 快速开始

### 2.1 前置条件

| 需要 | 说明 |
|---|---|
| JDK 21 | `java -version` 应显示 21 |
| Maven | 本机离线跑会缺插件依赖，**第一次要联网**补齐 |
| Node.js | 后端会 `ProcessBuilder` 起 `node` 子进程去跑飞猪 CLI |
| 飞猪 CLI | `npm i -g @fly-ai/flyai-cli`，装完用 `npm root -g` 找它的位置 |
| 飞猪凭据 | `~/.flyai/config.json` 里要有 `FLYAI_API_KEY`，同目录还有 `device-id` |
| DeepSeek Key | 自己去申请，配到环境变量里 |

> ⚠️ **飞猪 CLI 需要凭据才能查**，凭据放在 `~/.flyai/config.json`（`FLYAI_API_KEY`），
> 同目录还有一个 `device-id`。换机器 / 换容器部署时这两个要一起带过去 ——
> 换一个全新的 `device-id` 会不会被上游风控拦下，**没验证过**。

### 2.2 两个必须配的环境变量

**没有默认值，且故意不留默认值**（写进仓库就等于泄露）：

```bash
# 飞猪 CLI 的 bundle 绝对路径（注意指向 dist/flyai-bundle.cjs，不是那个 flyai.cmd）
# 找不到它：npm root -g
export FLYAI_CLI_PATH="$(npm root -g)/@fly-ai/flyai-cli/dist/flyai-bundle.cjs"

# DeepSeek 密钥
export DEEPSEEK_API_KEY=sk-xxxxxxxx
```

Windows（CMD）：`set FLYAI_CLI_PATH=...` / `set DEEPSEEK_API_KEY=sk-xxx`

两个都缺一不可：缺 `DEEPSEEK_API_KEY` **启动就失败**（不是用到才失败）——
一个连模型都连不上的规划服务让它起来只会骗人。

### 2.3 跑起来

```bash
cd backend
mvn spring-boot:run
```

若报 `Port 52562 was already in use`：环境里存在 `SERVER__PORT` 变量，
Spring Boot 的优先级是「命令行参数 > 环境变量 > 配置文件」，它会**悄悄盖掉**这里的 8003。
绕开：

```bash
unset SERVER__PORT && mvn spring-boot:run
```

### 2.4 先确认它活着

```bash
curl -s http://localhost:8003/api/health
# {"code":0,"message":"ok","data":{"status":"up","java":"21.x.x","time":"..."}}
```

---

## 3. 接口一览

全部返回统一信封（见第 4 节）。**HTTP 状态码恒为 200**，成败看 `code`。

| 方法 | 路径 | 作用 | 请求体 |
|---|---|---|---|
| GET | `/api/health` | 服务活着吗 | — |
| POST | `/api/plan/chat` | 对话，一轮轮把画像攒出来 | `{ messages, currentParams }` |
| POST | `/api/plan/generate` | 拿画像去查、去排，产出行程 | `{ params, retrieval?, maxTicketPriceYuan? }` |
| POST | `/api/retrieval/pool` | 给一个"期望"，拿回可追溯的候选池 | `{ city, startDate, days, wantedCategories, keywords, needHotel, maxTicketPriceYuan }` |
| GET | `/api/flyai/probe/poi` | **探针**：看飞猪原样吐了什么 | `?city=&category=&keyword=` |
| GET | `/api/flyai/probe/hotel` | **探针**：同上，酒店 | `?city=&poiName=&checkIn=&checkOut=` |

两个探针接口不做任何清洗、不筛选、不缓存，并且**会回显真实命令**。
它们的存在是为了在出问题时能证明「飞猪给的就是这样」，与"我们没把它改坏"分开归因。

**`/chat` 和 `/generate` 是前半段和后半段**，不是两个独立功能：
chat 的产物 `params` 正好是 generate 的入参，前端原样递过去即可，不用做任何转换。

实测耗时（这是架构的真实成本，不是调参能消掉的）：

| 接口 | 耗时 |
|---|---|
| `/chat` | 约 5~15 秒（一次模型调用） |
| `/generate` | 约 25~60 秒（检索 2~10s + 规划检索条件 10~20s + 排行程 10~30s） |

---

## 4. 统一响应约定与错误码

```json
{ "code": 0, "message": "ok", "data": { } }
```

`code = 0` 成功；非 0 时 **`message` 会被前端直接显示给用户**，
所以它必须是给人看的话，不能是 `NullPointerException at line 88`。

| code | 含义 | 用户该做什么 |
|---|---|---|
| `0` | 成功 | — |
| `2001` | 飞猪查询失败 | 多半是城市/条件太窄，改条件 |
| `4000` | 入参不合法 / 请求体读不出来 | 看自己发的东西，一次修好 |
| `5001` | 大模型调用失败 | 再点一次生成 |
| `5000` | 未预期错误 | 稍后重试 |

**错误码的用途不是分类，是让调用方知道该怎么办** ——
2001 和 5001 之所以不复用同一个码，就因为它们的下一步动作不一样。

这个约定的代价要说清楚：网关看状态码永远 200，真实错误率得从响应体里挖；
浏览器 `res.ok` 永远 true。要改的话是**三处一起改**：这里的约定、`ApiResult`、以及前端 `http.ts`。

---

## 5. 代码结构

按「变化的原因」分包，不按「文件类型」。

| 包 | 职责 | 什么时候会改 |
|---|---|---|
| `common` | `ApiResult` 信封、全局异常出口、JSON 工具 | 改响应约定时 |
| `health` | `/api/health` | 几乎不变 |
| `flyai` | 飞猪 CLI 接入：进程启动、参数拼装、原始 DTO（`raw/`）、城市名校验 | 飞猪 CLI 参数变了 |
| `candidate` | 原始数据 → 干净候选（规范化、去重、坐标） | 上游字段变了 |
| `retrieval` | 检索编排：限流闸门、缓存、城际车次挑选、候选池 | 检索策略变了 |
| `rule` | 白名单规则与 `RuleChecker` | 产品约束变了 |
| `llm` | 三个 Composer（对话 / 规划检索条件 / 排行程）+ 模型配置 | prompt 变了 |
| `plan` | 编排层 + **契约镜像**（对前端的 DTO 全在这里） | 接口契约变了 |

`plan` 包里的 `TravelPlan` / `TravelDay` / `TravelSpot` / `HotelStay` / `SpotOption`
是**对前端的契约镜像**，与前端 `src/types/plan.ts` 一一对应 —— 改字段两边要一起改。

### 三个 Composer 的分工

| 类 | 干什么 | 温度为什么是 0.3 |
|---|---|---|
| `ChatComposer` | 一句话 → 画像增量 + 下一句追问 | 结构要稳（其实它更想要 0.7，见下） |
| `RetrievalPlanner` | 画像 → 该查什么（城市/类别/关键词） | 要稳定的结构，不要创意 |
| `PlanComposer` | 画像 + 候选 → 排好的行程 | 同上 |

> 已知取舍：三条调用共用一个 `ChatModel` 单例，而 `temperature` 是它的构建参数，只能给一个值。
> 现在共用 `0.3` —— 因为**三个里有两个怕随机，只有一个怕生硬**，按多数派决策。
> 哪天"对话语气太板"成为真实抱怨，再拆成两个 bean。

---

## 6. 几条值得知道的设计

**① 事实由代码保证，模型只负责措辞。**
模型看到的候选行是后端渲染的，它只能回编号。名称、价格、地址、购票链接、经纬度按编号回填。

**② 真实车次时刻提前查出、喂进 prompt，再加兜底检查。**
日期只由 `startDate + days` 决定，不需要等排程结果，所以车次查询可以提前。
配 `firstDayConflict` / `lastDayConflict` 两条纯函数兜底 ——
修掉过一个真 bug：行程排着"下午 15:00 珠江夜游"，而回程车早上 06:18 就开走了。

**③ 酒店与景点的距离由后端算好写进候选行，校验层再独立算一遍。**
prompt 里不给模型经纬度数字（让 LLM 比两串坐标是碰运气），而是把结果告诉它：
`H5 | 广州奥园喜来登酒店 | ... | 近：C3(1.7km) C2(2.0km) C5(2.4km)`。
同时 `PlanService.warnGeoConflicts` 独立算一遍兜底 ——
**prompt 是优化，代码才是保证。**

**④ 距离工具只有一份实现。**
`plan/Geo.java` 与前端 `distanceKm()` 用**同一套公式**（等距圆柱，半径 6371）。
两份实现不一致，前后端对同一对点的远近排序就会不同。

**⑤ 冲突不拦行程，只写进 `warnings`。**
不指望模型永不犯错，但它犯了，界面上一定有一句人话告诉用户
（"第 2 天晚上住华发国际公寓，第 3 天第一个点是情侣路，却在约 19.5 公里以外"）。

**⑥ 限流不是优化，是能不能用的前提。**
实测：并发 6 个 → 3 个 429；连续串行不间隔 → 后 3 个 451（风控）；
冷却 90 秒后串行 + 间隔 7 秒 → 全部成功。所以有 `min-gap-ms` 这道闸门。

---

## 7. 配置速查

全部在 `src/main/resources/application.yaml`。**两个密钥类配置故意没有默认值。**

| 配置 | 默认 | 说明 |
|---|---|---|
| `kidtrip.flyai.node-path` | `node` | 交给 PATH 找；找不到就填 node.exe 绝对路径 |
| `kidtrip.flyai.cli-path` | `${FLYAI_CLI_PATH:}` | 飞猪 CLI 的 bundle 路径 |
| `kidtrip.flyai.timeout-seconds` | `120` | 单次 CLI 调用上限（冷启动 node ~750ms + 网络往返） |
| `kidtrip.flyai.min-gap-ms` | `3000` | 两次调用最小间隔（限流闸门，**别调 0**） |
| `kidtrip.flyai.cache-ttl-seconds` | `600` | 同条件结果缓存时长 |
| `kidtrip.flyai.hotel-stars` | `"4,5"` | 酒店星级筛选；空串 = 关掉（飞猪默认按价格升序，约等于把青旅排最前） |
| `kidtrip.flyai.max-leg-minutes` | `180` | 城际单段时长上限（带娃的核心约束，**没有"关掉"状态**） |
| `kidtrip.flyai.cache-file` | `""` | 空 = 纯内存；填路径则落盘（演示前预跑用，**要同时调长 ttl**） |
| `kidtrip.llm.api-key` | `${DEEPSEEK_API_KEY:}` | 缺了启动就失败 |
| `kidtrip.llm.base-url` | `https://api.deepseek.com` | 兼容 OpenAI 协议，换 URL 即可换厂商 |
| `kidtrip.llm.model-name` | `deepseek-chat` | |
| `kidtrip.llm.temperature` | `0.3` | 见第 5 节那个取舍 |
| `kidtrip.llm.timeout-seconds` | `90` | 出 3 天行程 JSON 实测 10~30 秒 |

---

## 8. 测试

```bash
cd backend
mvn test          # 222 个用例
mvn test -Dtest=LegServiceTest    # 单个类
```

**222 / 222 全绿**，19 个测试类 / 3609 行（另有 1 个测试替身 `FakeChatModel`）。分布（用例数）：

| 测试类 | 数量 | 护住什么 |
|---|---|---|
| `retrieval/LegServiceTest` | 22 | 城际车次挑选：去程/回程时段窗口、**最快的首班车不能胜出** |
| `plan/PlanAssemblerTest` | 23 | 装配：编号回填、缺字段、顺序 |
| `llm/ChatComposerTest` | 20 | 画像抽取与合并 |
| `plan/TravelParamsMergeTest` | 14 | 多轮对话里画像的增量合并 |
| `retrieval/FlyaiCacheStoreTest` | 13 | 缓存：命中、过期、坏文件不拦启动 |
| `llm/RetrievalPlannerTest` | 12 | 检索条件规划 |
| `flyai/FlyaiPropertiesTest` | 11 | 配置校验（含"缺 cli-path 启动即失败"） |
| `plan/PlanServiceGeoConflictTest` | 11 | 距离兜底校验（双向：有冲突必有警告、无冲突必无警告） |
| `plan/GeoTest` | 10 | 距离公式与边界（赤道 lat=0 不算缺值、缺坐标回 null 不是 0） |
| `plan/PlanServiceLegConflictTest` | 9 | 首末日与车次的冲突判定 |
| 其余 9 个类 | 77 | — |

**测试怎么写**：纯函数优先抽 `static`（`firstDayConflict` / `lastDayConflict` /
`spotSpreadConflict` / `morningCommuteConflict` / `Geo.distanceKm`），这样能直接测，
不用起 Spring 上下文。`FakeChatModel` 用来替掉真实模型。

---

## 9. 部署注意 / 已知限制

**部署时这个后端比一般 Web 服务多几件麻烦事**，先说清楚免得踩空：

1. **它要两个运行时**：JDK 21（跑 Jar）**和** Node.js（起子进程跑飞猪 CLI）。单一运行时镜像跑不起来。
2. **要三份凭据**：`DEEPSEEK_API_KEY` + 飞猪 `FLYAI_API_KEY` + `~/.flyai/device-id`。后两个在
   `~/.flyai/` 下，不在仓库里（这是对的，但意味着部署要单独注入）。
3. **新的 `device-id` 会不会被上游风控拦，未验证** —— 这是云部署最大的未知数。
   实测的限流阈值（429 / 451）都是在本机这台设备上测的。
4. `max-leg-minutes` / `hotel-stars` 这类产品约束的取值都有实测依据，
   写在配置注释里了，改之前先读注释。

**已知限制**：

- `/generate` 要 25~60 秒，目前只有一句等待文案。真正的解法是流式输出或分步返回，还没做。
- `HotelStay.rating` 实际上永远填不上 —— 飞猪酒店接口不带评分字段。要么删掉，要么显式显示"暂无评分"。
- 三条 LLM 调用共用一个温度（见第 5 节）。
- 多城市串联（如"广州+珠海"一次规划）没做。

---

MIT License · 见 [`../LICENSE`](../LICENSE)
