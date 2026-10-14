package com.hellen.kidtrip.plan;

import com.hellen.kidtrip.candidate.HotelCandidate;
import com.hellen.kidtrip.candidate.PoiCandidate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 候选编号 —— **C1、C2、C3……**（景点）和 **H1、H2、H3……**（酒店）
 *
 * 这个类只有 40 行，但它是"白名单"机制的**唯一真相来源**，所以不能省。
 * 编号会被用在两个地方，而且必须是同一套：
 *   ① 拼 prompt 时，把候选清单渲染成 `C1 | 深圳欢乐谷 | 主题乐园 | ¥227 | 地址`
 *   ② 模型返回后，用它把 `candidateId: "C1"` 查回真正的候选
 *
 * 如果这两处各写一遍编号逻辑（哪怕只是 1-based 和 0-based 差一位），
 * 就会出现最恶心的一类 bug：**行程看起来完全正常，但每个点都错位了一个**。
 * 抽成一个类，让"编号规则"只有一个定义处 —— 这就是为什么它值得单独存在。
 *
 * 为什么不用 poiId 当编号让模型直接引用：
 *   飞猪的 id 是 `763466299820` / `1085229431247` 这样的长数字串。
 *   模型是逐 token 生成的，抄长数字串的出错率明显更高，而且抄错之后
 *   你要面对的是"一个看起来像 id 的东西查不到"——排障成本远高于 C1..Cn。
 *   短编号还有个附带好处：**模型能一眼看出清单总共有几条**，
 *   它就不会随手编一个 C99 出来（编了也会被丢弃）。
 *
 * ── 为什么要**两套前缀**（C 和 H），而不是把酒店也编进 C 序号 ──────────
 * 因为景点和酒店在行程里占的是**两个不同的槽位**（`spots[]` 和 `hotel`），
 * 而模型填槽位是会串的：给它一份混编的 C 清单，它有相当概率把酒店写进 items、
 * 或者把景点名写成住宿。一旦混在一起，这类错误**查不出来** ——
 * C5 确实存在，只是它是个酒店，它会被当成景点排进上午 9:30 的行程里，全程不报错。
 * 分成 C/H 之后，同样的错误会立刻变成"编号不在清单里"，走已有的丢弃 + 记账路径。
 * **这和 kind 必须写成 getKind() 是同一类防御：让写错不再静默。**
 */
public final class CandidateCodes {

    /** 景点编号前缀。改它要同时改 prompt 里的说明，所以留个常量而不是散在字符串里 */
    public static final String PREFIX = "C";

    /** 酒店编号前缀。和景点故意不同 —— 见类注释里"两套前缀"那段 */
    public static final String HOTEL_PREFIX = "H";

    /**
     * 每家酒店后面挂几个"最近的候选景点"。
     *
     * 3 个够模型看出"这家挨着哪一片"；再多就只是把 prompt 撑长，
     * 而一天只要一家酒店，真正影响决策的从来不是第 4 近的是谁。
     */
    private static final int NEARBY_SPOTS_PER_HOTEL = 3;

    /**
     * 模型想表达"**当晚不需要住宿**"时写的值。
     *
     * 为什么要造一个小协议，而不是"留空"：
     *   Jackson 里"字段没写"和"字段是 null"解析出来是同一个 null，分不开。
     *   可这两件事在产品上差别极大 ——
     *     "没写" = 模型漏了（该沿用前一天的住处）
     *     "写 none" = 当天返程，就是不住（不能补，补了就变成凭空多一晚）
     *   给"明确说不"一个显式的词，是唯一能把两者分开的办法。
     *   这几个值都要接受：模型可能写 none / 无 / null / 0，都当"明确不住"。
     */
    public static final String STAY_NONE = "none";

    private static final java.util.Set<String> NONE_WORDS =
            java.util.Set.of("none", "无", "null", "no", "-", "0", "不需要", "不住", "na", "n/a");

    /** 这一天的住宿回答，是不是"明确不住"。空 / null 都不算 —— 那叫"没回答" */
    public static boolean isStayNone(String raw) {
        if (raw == null) {
            return false;
        }
        return NONE_WORDS.contains(raw.strip().toLowerCase());
    }

    private CandidateCodes() {
    }

    /**
     * 建"编号 → 候选"的索引。**唯一**一处定义编号规则的地方。
     *
     * 用 LinkedHashMap：保持候选池原本的顺序。
     * 顺序有意义 —— 池子里靠前的是飞猪认为更相关的（榜单排名靠前的先回来），
     * 模型面对一份有序清单，选择质量比面对乱序清单要好。
     */
    public static Map<String, PoiCandidate> index(List<PoiCandidate> pois) {
        Map<String, PoiCandidate> byCode = new LinkedHashMap<>();
        if (pois == null) {
            return byCode;
        }
        for (int i = 0; i < pois.size(); i++) {
            byCode.put(PREFIX + (i + 1), pois.get(i));
        }
        return byCode;
    }

    /**
     * 把候选清单渲染成给模型看的一行行文本。
     *
     * 每一行给什么信息，是一次真实的取舍：
     *   ✅ 名称、类别、票价、榜单 / 等级、地址（截断）—— 这些是模型"挑"的依据
     *   ❌ 坐标、poiId、bookUrl —— 模型用不到，写进去只是白烧 token
     *
     * 票价那一栏的写法值得留意：没有价格时写"(无票价)"，**不是写 "¥0"**。
     * "无票价"不等于"免费"（可能是免费公园，也可能是门票含在母景区里），
     * 写成 ¥0 会让模型以为"这个不要钱，多排两个" —— 一句小小的措辞差异，
     * 就会在行程里变成一个错误的成本预期。
     */
    public static List<String> renderLines(List<PoiCandidate> pois) {
        List<String> lines = new ArrayList<>();
        if (pois == null) {
            return lines;
        }
        for (int i = 0; i < pois.size(); i++) {
            PoiCandidate p = pois.get(i);
            lines.add(PREFIX + (i + 1) + " | " + p.name()
                    + " | " + blankTo(p.category(), "未分类")
                    + " | " + (p.priceYuan() == null ? "(无票价)" : "¥" + trimZero(p.priceYuan()))
                    + " | " + blankTo(p.rank(), p.level() == null ? "无榜单信息" : p.level() + "A")
                    + " | " + truncate(blankTo(p.address(), "地址未知"), 22));
        }
        return lines;
    }

    /** 建"酒店编号 → 候选"的索引。规则和景点那套完全一样，只是前缀换成 H。 */
    public static Map<String, HotelCandidate> indexHotels(List<HotelCandidate> hotels) {
        Map<String, HotelCandidate> byCode = new LinkedHashMap<>();
        if (hotels == null) {
            return byCode;
        }
        for (int i = 0; i < hotels.size(); i++) {
            byCode.put(HOTEL_PREFIX + (i + 1), hotels.get(i));
        }
        return byCode;
    }

    /**
     * 把酒店清单渲染成给模型看的一行行文本。
     *
     * 给什么、不给什么，和景点那份的理由基本相同（见上面 renderLines）：
     *   ✅ 名称、星级、评分、**每晚房价**、地址（截断）—— 这些是模型"挑住哪"的依据
     *   ❌ hotelId、bookUrl —— 模型用不到，写进去只是白烧 token
     *
     * 房价那一栏写成 `¥388/晚`，那个"/晚"必须带上：
     *   如果不写单位，模型会把房价和门票放在同一个量纲里比较，
     *   于是它可能因为"酒店 388 比门票 227 贵"而倾向少住 —— 这是个纯粹的措辞造成的错误推理。
     *   和景点那边"(无票价)"不能写成"¥0"是同一条规矩：**清单上的每个字都在影响模型的判断。**
     *
     * ── 行尾那段「近：C1(0.7km) C24(0.9km)」值得单独说 ────────────────
     *   在这之前，坐标是**故意不给**的（理由和上面一样），但**距离也一并没给** ——
     *   于是模型挑酒店时手里只剩酒店名和 22 个字的地址，只能猜。
     *   实测它靠"长隆企鹅酒店"猜对了 0.33 公里、靠"地球村雅致漓江酒店（…象鼻山店）"
     *   猜对了 0.70 公里；可同一次行程里，它让用户第 1 天住桂林市区、
     *   第 2 天一早去阳朔 —— 中间 **42.4 公里** —— 而它全程不知道这件事。
     *
     *   ⚠️ 关键在于分清两件事：**坐标不直接给，距离必须给。**
     *      坐标是"23.00266, 113.315593"这样的数字串，模型拿着它算不了距离，
     *      塞进 prompt 确实只是烧 token；而"离这几个点 0.7 公里"是**已经算好的结论**，
     *      正是它做选择时需要的东西。
     *      **把工具给它没用，把答案给它才有用。**
     *
     * @param pois **必须是这次真正送进 prompt 的那一批候选**，不能是全量池。
     *             否则会标出 C35 这种模型压根没见过的编号 —— 一个它用不上的提示
     *             比没有提示更糟：它会以为自己漏看了什么，然后开始编。
     */
    public static List<String> renderHotelLines(List<HotelCandidate> hotels, List<PoiCandidate> pois) {
        List<String> lines = new ArrayList<>();
        if (hotels == null) {
            return lines;
        }
        for (int i = 0; i < hotels.size(); i++) {
            HotelCandidate h = hotels.get(i);
            lines.add(HOTEL_PREFIX + (i + 1) + " | " + h.name()
                    + " | " + blankTo(h.star(), "星级未知")
                    + " | " + (h.rating() == null ? "暂无评分" : h.rating() + " 分")
                    + " | " + (h.priceYuan() == null ? "(无房价)" : "¥" + trimZero(h.priceYuan()) + "/晚")
                    + " | " + truncate(blankTo(h.address(), "地址未知"), 22)
                    + " | 近：" + nearbySpots(h, pois));
        }
        return lines;
    }

    /**
     * 这家酒店最近的几个**候选**景点，形如 `C1(0.7km) C24(0.9km) C21(1.8km)`。
     *
     * 为什么是"最近的几个"而不是"清单里的前几个"：
     *   候选清单的顺序是飞猪的相关性排名，和地理位置毫无关系。
     *   直接取前三条，等于给模型看了一个与距离无关的采样 —— 那比不给还糟。
     *
     * 编号写成 `PREFIX + (i+1)`，**和 renderLines 是同一套规则**。
     *   这里要是随手写成 0-based，模型会把 C2 当成清单里的第二个点，而它其实是第三个。
     *   "每个点都错位一位"正是 CandidateCodes 这个类当初被抽出来防的东西，
     *   所以宁可在这里重写一遍 (i+1)，也不抽一个"看起来更简洁"的公共方法 ——
     *   那个方法会让两处编号之间多出一层间接，而错位恰恰是间接造成的。
     */
    private static String nearbySpots(HotelCandidate hotel, List<PoiCandidate> pois) {
        if (pois == null || pois.isEmpty() || !Geo.has(hotel.lat(), hotel.lng())) {
            return "未知";
        }
        List<Nearby> found = new ArrayList<>();
        for (int i = 0; i < pois.size(); i++) {
            PoiCandidate p = pois.get(i);
            Double km = Geo.between(hotel.lat(), hotel.lng(), p.lat(), p.lng());
            if (km != null) {
                found.add(new Nearby(PREFIX + (i + 1), km));
            }
        }
        if (found.isEmpty()) {
            // 酒店有坐标，但一条景点都没有坐标 —— 这是"算不出来"，不是"都很远"。
            // 两者都写"未知"是对的，但只有这一条路径是数据缺失，值得单独返回一次。
            return "未知";
        }
        found.sort(Comparator.comparingDouble(Nearby::km));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(NEARBY_SPOTS_PER_HOTEL, found.size()); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            Nearby n = found.get(i);
            sb.append(n.code()).append('(').append(Geo.label(n.km())).append(')');
        }
        return sb.toString();
    }

    /** 一个候选景点和这家酒店的距离。只是个给排序用的临时对，不出这个类 */
    private record Nearby(String code, double km) {
    }

    private static String blankTo(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s.strip();
    }

    private static String trimZero(Double d) {
        return d == Math.floor(d) ? String.valueOf(d.longValue()) : String.valueOf(d);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
