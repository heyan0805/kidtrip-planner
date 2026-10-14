/**
 * 探针共用的"本机路径"解析 —— 全仓库唯一一处写这些路径的地方。
 *
 * ── 为什么要有它 ────────────────────────────────────────────────
 *   这些路径原本是**写死**在二十来个脚本里的绝对路径，形如
 *       C:/Users/<某个用户名>/AppData/Roaming/npm/node_modules/@fly-ai/...
 *   两个问题：
 *     ① 开源时把本机用户名一起带了出去；
 *     ② 换台机器 / 换个用户，二十个脚本会一起失效，而且要改二十个地方。
 *
 *   现在统一从 os.homedir() 推出来，**每一个都允许用环境变量覆盖** ——
 *   因为"CLI 装在哪"是这台机器的事实，不是这个项目的事实。
 *
 * ── 路径找不到时怎么办 ──────────────────────────────────────────
 *   · 飞猪 CLI   → `npm root -g` 会告诉你全局 node_modules 在哪
 *   · Playwright → 自己 `npm i playwright` 一份，再把 PW_WORKSPACE 指过去
 */
import { homedir } from 'node:os'
import path from 'node:path'

/** npm 全局 node_modules。Windows 上默认在 %APPDATA%/npm/node_modules */
export const NPM_GLOBAL = process.env.NPM_GLOBAL
  ?? path.join(homedir(), 'AppData/Roaming/npm/node_modules')

/** 飞猪 CLI 的 bundle。Java 执行不了 Windows 的 .cmd shim，所以直接让 node 跑它 */
export const FLYAI_CLI = process.env.FLYAI_CLI
  ?? path.join(NPM_GLOBAL, '@fly-ai/flyai-cli/dist/flyai-bundle.cjs')

/** 预跑缓存（就在本目录）。探针和后端要指向**同一个**文件，否则验的不是同一份数据 */
export const CACHE_FILE = process.env.CACHE_FILE
  ?? path.join(import.meta.dirname, 'flyai-cache.json')

/** 装 Playwright 的那份 node_modules */
export const PROBE_WORKSPACE = process.env.PW_WORKSPACE
  ?? path.join(homedir(), '.workbuddy/binaries/node/workspace')

/** 后端日志。默认放系统临时目录，可用 GEO_LOG 覆盖 */
export const PROBE_LOG = process.env.GEO_LOG
  ?? path.join(process.getBuiltinModule('node:os').tmpdir(), 'geo-backend.log')
