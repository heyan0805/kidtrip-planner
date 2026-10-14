/**
 * 飞猪 FlyAI 的**原始返回形状**。
 *
 * 这个文件为什么单独存在（而不是塞进 plan.ts）：
 *   因为它和 plan.ts 的"变化原因"不同。
 *   - plan.ts 是我们自己的契约，我们改接口时才变；
 *   - 这个文件是第三方的形状，飞猪改字段时它就变。
 *   让它们分开，飞猪改字段时只会弄坏这一个文件，不会污染我们的契约。
 *   → 规矩：同一个文件里的东西，应该"因为同一个原因而变化"。
 *
 * 类型刻意**照着实测原样声明，不做任何美化**（price 是字符串、坐标是字符串）。
 * 美化的活由 API 到候选池之间的"规范化"那一步做，不在这里做。
 * 实测命令：flyai search-poi --city-name 深圳 --category 动物园
 */

/* ============================================================
 * 一、输入侧：CLI 参数的可选值
 * ============================================================ */

/**
 * search-poi 的 --category 只认这 35 个**中文**值。
 * ⚠️ `flyai search-poi --help` 里印的是英文（zoo / museum / theme park），
 *    实测传英文会被 CLI 直接拒绝并让你从中文枚举里选。--help 是错的。
 * 把它写成联合类型，就是把这个"文档没告诉你的约束"变成编译期强制。
 */
export type PoiCategory =
  | '自然风光' | '山湖田园' | '森林丛林' | '峡谷瀑布' | '沙滩海岛'
  | '沙漠草原' | '人文古迹' | '古镇古村' | '历史古迹' | '园林花园'
  | '宗教场所' | '公园乐园' | '主题乐园' | '水上乐园' | '影视基地'
  | '动物园' | '植物园' | '海洋馆' | '体育场馆' | '演出赛事'
  | '剧院剧场' | '博物馆' | '纪念馆' | '展览馆' | '地标建筑'
  | '市集' | '文创街区' | '城市观光' | '户外活动' | '滑雪'
  | '漂流' | '冲浪' | '潜水' | '露营' | '温泉'

/** search-hotel 的 --hotel-types */
export type HotelType = 'hotel' | 'homestay' | 'inn'

/** search-hotel 的 --sort */
export type HotelSort =
  | 'distance_asc'
  | 'rate_desc'
  | 'price_asc'
  | 'price_desc'
  | 'no_rank'

/* ============================================================
 * 二、输出侧：所有子命令共用的外层信封
 * ============================================================ */

/**
 * 所有子命令共用的外层信封。实测两种形态（**别照直觉写**）：
 *   成功   → { "data": { "itemList": [...] }, "message": "success", "status": 0, "systemMessage": null }
 *   无结果 → { "data": null,                  "message": "empty",   "status": 0, "systemMessage": null }
 *
 * 两个坑：
 * ① message / status / systemMessage 在**顶层**，不在 data 里（data 里只有 itemList）。
 * ② **无结果时 status 也是 0**，和成功一模一样 → 不能靠 status 判断有没有数据。
 *    必须判 `data !== null`（或 message === 'success'），否则会把"没查到"当成功，
 *    更直接的是：`data` 为 null 时访问 `data.itemList()` 在 Java 侧就是一个 NPE。
 */
export interface FlyaiResponse<T> {
  data: { itemList: T[] } | null
  message: string
  status: number
  systemMessage: string | null
}

/* ============================================================
 * 三、search-poi 的输出
 * ============================================================ */

/** 单条门票信息。实测 10 条景点里只有 1 条有它 —— 其余 9 条 ticketInfo 是 null */
export interface FlyaiTicket {
  itemId: string
  /** "¥198" —— 带货币符号的**字符串**，不是数字 */
  price: string
  /** "大门票 成人票" */
  ticketName: string
  priceDate: string | null
  rate: number | null
  commissionMoneyYuan: number | null
  skuCommissionStruct: unknown
}

export interface FlyaiPoi {
  /** 景点 id，如 "149220"。**字符串**，不能当数字用 */
  id: string
  name: string
  address: string
  category: string
  description: string
  /** "22.596039" —— 又一个字符串数字 */
  latitude: string
  /** "113.972146" */
  longitude: string
  mainPic: string
  /** ⚠️ 追踪短链，每次请求都重新生成（尾段是随机码）。不是稳定 URL，别当标识用 */
  jumpUrl: string
  /** "4" 或 null —— 同一个字段两种类型，且 4 是字符串 */
  poiLevel: string | null
  /** "深圳4A/5A景点榜第1名" 或 null */
  listRank: string | null
  freePoiStatus: string | null
  /** 可能整个是 null */
  ticketInfo: FlyaiTicket | null
}

/* ============================================================
 * 四、search-hotel 的输出
 * ============================================================ */

export interface FlyaiHotel {
  /** 酒店 id，如 "74895439" */
  shId: string
  name: string
  address: string
  latitude: string
  longitude: string
  mainPic: string
  detailUrl: string
  /** 同样是 "¥12" 这种字符串 */
  price: string
  /** ⚠️ 实测可能是 " "（一个空格）、"经济型"，也可能是 null。空格是真值！ */
  star: string | null
  rate: number | null
  brandName: string | null
  decorationTime: string | null
  /** "近海雅缤纷城(宝安店)" */
  interestsPoi: string | null
  commissionMoneyYuan: number | null
}
