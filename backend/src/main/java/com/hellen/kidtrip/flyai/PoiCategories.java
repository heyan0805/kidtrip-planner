package com.hellen.kidtrip.flyai;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * `search-poi --category` 认得的**全部 35 个中文类别**。
 *
 * 为什么它属于 flyai 包，而不是 plan 或 llm 包：
 *   这不是"我们的概念"，是**飞猪 CLI 的事实** —— 和 FlyaiPoi 里那些字段名同一性质。
 *   飞猪哪天加了新类别，改的是这里，而不是业务代码。
 *   判断归属只需要问一句：它因为什么而变化？
 *   　飞猪改接口 → 变 → 所以在 flyai 包；我们改产品 → 变 → 那就该在 plan 包。
 *
 * 它为什么必须存在（B5 之前后端一直没有它）：
 *   B5 之前，类别只从外面(脚本)传进来，后端只做"有没有出现"的比对，不需要知道全集。
 *   B5 开始，**类别由大模型输出** —— 于是"模型不能自己造类别"就成了必须挡的一件事。
 *   这就是 CandidateCodes 那套白名单换了张脸：**不是相信模型不会编，而是让它编了也没用。**
 *
 * ⚠️ `flyai search-poi --help` 里印的是英文（zoo / museum / theme park），
 *    实测传英文会被 CLI 直接拒绝并让你从中文枚举里选。**--help 是错的，实测为准。**
 *    前端 `types/flyai.ts` 里的 `PoiCategory` 是同一份列表的联合类型 ——
 *    两边必须一起改，所以这里和那里都留了指回来的注释。
 */
public final class PoiCategories {

    /** 35 个类别，顺序照实测 --help 的中文枚举原样，不重排（重排会让人以为有含义） */
    public static final List<String> ALL = List.of(
            "自然风光", "山湖田园", "森林丛林", "峡谷瀑布", "沙滩海岛",
            "沙漠草原", "人文古迹", "古镇古村", "历史古迹", "园林花园",
            "宗教场所", "公园乐园", "主题乐园", "水上乐园", "影视基地",
            "动物园", "植物园", "海洋馆", "体育场馆", "演出赛事",
            "剧院剧场", "博物馆", "纪念馆", "展览馆", "地标建筑",
            "市集", "文创街区", "城市观光", "户外活动", "滑雪",
            "漂流", "冲浪", "潜水", "露营", "温泉"
    );

    /** 查表用。用 Set 而不是每次遍历 List —— 这个东西会被逐个调用很多次 */
    private static final Set<String> SET = Set.copyOf(ALL);

    private PoiCategories() {
    }

    public static int size() {
        return ALL.size();
    }

    /**
     * 这个类别飞猪认不认。
     *
     * 做了 strip —— 模型偶尔会给" 动物园"这种带空白的值，
     * 不 trim 的话它会被当成非法类别丢掉，然后用户看到"期望的类别没找到"，
     * 而实际上是**我们自己**把一个正确值判成了非法。一个 trim 换掉一条假告警。
     */
    public static boolean isValid(String raw) {
        return raw != null && SET.contains(raw.strip());
    }

    /**
     * 白名单过滤：把模型给的类别里**飞猪认得的**留下，最多 max 个。
     *
     * 保序（LinkedHashSet）：模型排在前面的更重要，而 wantedCategories 的顺序
     * 决定了"先补查哪一个"（见 RetrievalService.supplementTargets），所以顺序有意义。
     *
     * 去重也是顺手做的：模型有时会写两遍同一个类别，
     * 留着会让"期望覆盖"这个判断把同一件事算两次。
     *
     * @param max 上限，&lt;= 0 表示不限
     */
    public static List<String> keepValid(Collection<String> raw, int max) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String s : raw) {
            if (s == null) {
                continue;
            }
            String v = s.strip();
            if (SET.contains(v)) {
                out.add(v);
            }
            if (max > 0 && out.size() >= max) {
                break;
            }
        }
        return List.copyOf(out);
    }

    /**
     * 渲染成给模型看的一行。
     *
     * 用顿号分隔而不是 JSON 数组：这一行是要模型**理解**的（"可选值是这些"），
     * 不是要它解析的。和 PlanComposer.renderParams 是同一条规矩 ——
     * 要模型理解语义的地方写人话，要机器精确解析的地方写 JSON。
     */
    public static String renderForPrompt() {
        return String.join("、", ALL);
    }
}
