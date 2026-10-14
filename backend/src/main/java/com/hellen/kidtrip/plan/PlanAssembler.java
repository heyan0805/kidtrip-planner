package com.hellen.kidtrip.plan;

import com.hellen.kidtrip.candidate.HotelCandidate;
import com.hellen.kidtrip.candidate.PoiCandidate;
import com.hellen.kidtrip.llm.PlanDraft;
import com.hellen.kidtrip.rule.PlanRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 装配器：把模型的**草稿**变成一份可以展示的**行程**。
 *
 * 这是整个 B4 里最重要的一段代码，而且它有一个很少见的性质：**纯函数**。
 *   输入：草稿 + 候选池 + 画像 + 规则
 *   输出：行程 + 告警
 *   没有 IO、没有 Spring、不调模型、不看时钟 —— 同样输入永远同样输出。
 *
 * 于是它**可以被单元测试**（PlanAssemblerTest）。这件事的意义比"代码更整洁"大得多：
 * 在此之前，我们所有的验证都得靠"真跑一次、用眼睛看结果"；
 * 从这一步开始，"模型不听话时会发生什么"可以在 5 毫秒内被反复验证，
 * 而不是每次花 20 秒和一次 token 费用去试。
 *
 * 能变成纯函数，正是因为前面那个决定：**让模型只输出 candidateId**。
 * 如果模型直接输出名字和价格，那"它到底写对没有"就只能靠人看 —— 无法测。
 * **可测性是设计出来的，不是补出来的。**
 *
 * 它要挡住模型的五类不听话（前四类都实测发生过）：
 *   ① 引用了清单里没有的编号（编造）→ 丢弃 + 记账
 *   ② 同一个景点排了两次 → 第二次丢弃 + 记账
 *   ③ 某天排得超过上限 → 截断 + 记账
 *   ④ 天数不对（多排 / 少排）→ 按画像的天数对齐 + 记账
 *   ⑤ 去重之后某一天变空了 → **从没用上的候选里本地补排** + 记账
 * 一句话：**凡是"数量"和"引用"这类能机械校验的东西，一律不信模型，本地重算。**
 * 模型只负责那些机械校验不了的：挑哪个、什么时候、提示什么。
 *
 * ⑤ 是实测撞出来的，值得单独说：第一次真跑，模型把 C9（野生动物园）在第 1 天和第 2 天
 * 各排了一次 —— 它不知道"一个景点只能去一次"。去重之后第 2 天变成空的，
 * 用户看到的是一份"3 天行程中间那天什么都没有"的结果。
 * 我们在 prompt 里补了这条要求，**但兜底放在这里** ——
 * 因为 prompt 里的每一条要求都是"优化"，不是"保证"。
 *
 * ── 住宿（每晚住哪）和景点是**两套相反的规则**，别搞混 ────────────────
 *   景点：同一个只能出现一次 → 靠 usedPoiIds 去重（上面 ②）
 *   酒店：同一家**可以连着住好几晚** → 绝不能去重，否则"连住"会被毁掉
 * 所以下面住宿那段的计数器和分支是独立的一套，故意不和景点共用。
 * 这不是重复代码，是**两件事长得像但规则相反**。
 * 顺手一提：把两段合成一段"通用去重"，是这里最容易被重构坏的地方。
 */
public final class PlanAssembler {

    private static final Logger log = LoggerFactory.getLogger(PlanAssembler.class);

    /** time 缺失或格式不对时的兜底时刻表，按一天内的第几个点取 */
    private static final String[] DEFAULT_TIMES = {"09:30", "13:30", "15:30", "18:00"};

    /** 本地补排出来的那一天用什么主题 */
    private static final String FILLED_THEME = "灵活安排";

    private PlanAssembler() {
    }

    /** 装配结果。刻意**不含 provenance** —— 那是编排层的账，不是装配的活 */
    public record Assembled(String summary, List<TravelDay> days, List<String> warnings) {
    }

    public static Assembled assemble(PlanDraft draft, List<PoiCandidate> pois, List<HotelCandidate> hotels,
                                     TravelParams params, PlanRules rules) {
        TravelParams p = params == null
                ? TravelParams.empty()
                : params;
        int wantDays = p.days() == null || p.days() <= 0 ? 3 : p.days();
        int perDay = rules.spotsPerDay(p.pace());

        Map<String, PoiCandidate> byCode = CandidateCodes.index(pois);
        // 住宿：独立的索引、独立的计数器。**没有 usedHotelIds 这种东西** —— 见类注释最后一段。
        List<HotelCandidate> hotelPool = hotels == null ? List.of() : hotels;
        Map<String, HotelCandidate> hotelByCode = CandidateCodes.indexHotels(hotelPool);
        List<String> notices = new ArrayList<>();

        // 上一晚住哪儿。用来实现"模型第 2 天没写 → 沿用第 1 天那家"（= 连住）
        HotelCandidate lastStay = null;
        int stayExplicit = 0;
        int stayInherited = 0;
        int stayFilled = 0;
        int stayUnknown = 0;
        int stayNone = 0;

        List<PlanDraft.Day> rawDays = draft == null || draft.days() == null ? List.of() : draft.days();
        if (rawDays.size() > wantDays) {
            notices.add("模型排了 " + rawDays.size() + " 天，已按画像的 " + wantDays + " 天截断");
        }

        Set<String> usedPoiIds = new LinkedHashSet<>();
        List<TravelDay> days = new ArrayList<>();
        int droppedUnknown = 0;
        int droppedDup = 0;
        int droppedOverflow = 0;
        int patchedTime = 0;

        for (int i = 0; i < wantDays; i++) {
            PlanDraft.Day raw = i < rawDays.size() ? rawDays.get(i) : null;
            // 主题是"表达"，模型不给就我们兜一个。永远不要让它变成 null 流到界面上 ——
            // 前端渲染一个 undefined 的主题，比渲染"第 2 天"糟糕得多。
            String theme = raw == null || isBlank(raw.theme())
                    ? "第 " + (i + 1) + " 天"
                    : raw.theme().strip();

            List<PlanDraft.Item> items = raw == null || raw.items() == null ? List.of() : raw.items();
            List<TravelSpot> spots = new ArrayList<>();

            for (PlanDraft.Item item : items) {
                if (item == null) {
                    continue;
                }
                if (spots.size() >= perDay) {
                    droppedOverflow++;
                    continue;
                }
                String code = item.candidateId() == null ? null : item.candidateId().strip();
                PoiCandidate poi = code == null ? null : byCode.get(code);
                if (poi == null) {
                    // 这就是白名单机制兑现的地方：模型编了清单外的编号，在这里被拦下。
                    // 注意它**不需要**任何"检测幻觉"的复杂逻辑 —— 一次 Map 查表就够了。
                    droppedUnknown++;
                    log.warn("模型引用了候选清单外的编号「{}」（第 {} 天），已丢弃", code, i + 1);
                    continue;
                }
                if (!usedPoiIds.add(poi.poiId())) {
                    droppedDup++;
                    continue;
                }

                String time = normalizeTime(item.time(), spots.size());
                if (!time.equals(item.time())) {
                    patchedTime++;
                }
                spots.add(toSpot(poi, time, isBlank(item.tips()) ? null : item.tips().strip()));
            }

            // ── 当晚住哪 ──────────────────────────────────────────────────
            String rawCode = raw == null ? null : raw.hotelCandidateId();
            if (isUnknownHotelCode(rawCode, hotelPool, hotelByCode)) {
                stayUnknown++;
            }
            StayDecision stay = decideStay(rawCode,
                    raw == null ? null : raw.hotelTips(),
                    hotelByCode, hotelPool, lastStay, i == wantDays - 1);
            switch (stay.source()) {
                case EXPLICIT -> stayExplicit++;
                case INHERITED -> stayInherited++;
                case FILLED -> stayFilled++;
                case NONE -> stayNone++;
                // 没有酒店数据时不重复报警 —— 检索层已经说过"没有查到可用的酒店"了
                case POOL_EMPTY -> { }
            }
            if (stay.hotel() != null) {
                lastStay = stay.hotel();
            }

            // transfer 这里**刻意留 null** —— 它不是"回填模型草稿"的一部分，
            // 而是一次独立的检索结果（真实车次）。谁去查、落到哪天，是编排层的事：
            // PlanService 排完之后统一补上（见 PlanService.attachLegs）。
            // 让它在这里留 null 而不是顺手传一个占位对象，是为了让"这一天还没有交通信息"
            // 和"这一天不需要交通"在数据上分得开 —— 前者会被编排层填掉，后者不会被。
            days.add(new TravelDay(i + 1, theme, spots, null,
                    stay.hotel() == null ? null : toStay(stay.hotel(), stay.tips())));
        }

        // ── 本地补排：模型留了空天，而池子里还有没用上的候选 ──────────────────
        // 为什么这是"必须"而不是"锦上添花"：见类注释 ⑤ —— 第一次真跑就撞上了。
        // 为什么不用"再问模型一次"：那要再花 20 秒和一笔 token，而且它还可能在别处犯错。
        //   补排是纯本地操作：零成本、零不确定性，填进去的同样是可追溯的真实候选。
        // 回退手段的三级优先又出现了一次（和 B3 的"放宽 vs 补查"是同一条）：
        //   本地重算（免费）> 再问模型（花钱且不确定）> 直接报错。
        List<PoiCandidate> unused = new ArrayList<>();
        for (PoiCandidate c : pois) {
            if (!usedPoiIds.contains(c.poiId())) {
                unused.add(c);
            }
        }
        int filledDays = 0;
        int filledSpots = 0;
        if (!unused.isEmpty()) {
            int cursor = 0;
            for (int i = 0; i < days.size() && cursor < unused.size(); i++) {
                TravelDay d = days.get(i);
                if (!d.spots().isEmpty()) {
                    continue;
                }
                List<TravelSpot> filled = new ArrayList<>();
                while (cursor < unused.size() && filled.size() < perDay) {
                    filled.add(toSpot(unused.get(cursor++), DEFAULT_TIMES[filled.size()], null));
                }
                if (!filled.isEmpty()) {
                    filledDays++;
                    filledSpots += filled.size();
                    // 主题必须换掉：模型给的主题（比如"室内动物奇遇"）对应的是它自己想排的那几个点，
                    // 而现在这一天里放的是别的东西。留一个不相符的主题，比没有主题更误导。
                    // 住宿那栏照旧带上 —— 补排的是"白天去哪"，和"晚上住哪"是两件事，
                    // 顺手把住宿也清掉会凭空抹掉模型排好的连住。
                    // 交通那栏同理带上：它跟"白天去哪"更没关系。
                    days.set(i, new TravelDay(d.day(), FILLED_THEME, filled, d.transfer(), d.hotel()));
                }
            }
        }

        // ── 记账：把"模型没听话"变成用户看得见的一句话 ────────────────────
        // 为什么宁可让用户看到"模型引用了不存在的编号"，也不悄悄丢掉：
        //   悄悄丢掉的结果是用户拿到一份**少了一个点的行程，而且永远不知道为什么**。
        //   而我们整个架构的可信度就建立在"你能看见每一步发生了什么"之上。
        if (droppedUnknown > 0) {
            notices.add("模型引用了 " + droppedUnknown + " 个候选清单里不存在的编号，已丢弃"
                    + "（这正是我们不让它直接写景点名的原因）");
        }
        if (droppedDup > 0) {
            notices.add("有 " + droppedDup + " 个景点被重复安排，重复的已丢弃");
        }
        if (droppedOverflow > 0) {
            notices.add("有 " + droppedOverflow + " 个点超过每天 " + perDay + " 个的上限，已截断");
        }
        if (patchedTime > 0) {
            notices.add("有 " + patchedTime + " 个时间点模型没给出有效格式，已用默认时刻补上");
        }
        if (filledDays > 0) {
            notices.add("有 " + filledDays + " 天模型没有排出有效的安排，已从候选池里补排 "
                    + filledSpots + " 个点（那天的主题改为「" + FILLED_THEME + "」）");
        }
        // 住宿的两条告警。注意**"沿用前一晚"不在这里** —— 那是连住的正常表达，不该报警（见 decideStay）。
        if (stayUnknown > 0) {
            notices.add("有 " + stayUnknown + " 天的住宿编号不在候选清单里，已按默认住处处理"
                    + "（住处的编号是 H 开头的，不是景点那种 C 开头）");
        }
        if (stayFilled > 0) {
            notices.add("有 " + stayFilled + " 晚模型没给出住处，已从候选酒店里补上一家");
        }
        if (days.stream().allMatch(d -> d.spots().isEmpty())) {
            notices.add("这次没有排出任何有效景点，多半是模型没有按候选清单编号来写，请再生成一次");
        }

        // 住宿是怎么凑出来的：模型指定几晚、沿用几晚、本地补几晚、明确不住几晚。
        // 这四个数字在排障时比读一遍 days 快得多 —— 尤其"沿用"很多的时候，
        // 一眼就能看出模型是在偷懒，还是在正确表达连住。
        log.info("住宿装配：模型指定 {} 晚 / 沿用前一晚 {} 晚 / 本地补 {} 晚 / 明确不住 {} 晚",
                stayExplicit, stayInherited, stayFilled, stayNone);

        String summary = draft == null || isBlank(draft.summary())
                ? "已为你规划好行程，可按需要调整。"
                : draft.summary().strip();

        // 顺序：**先我们的告警，后模型的建议**。
        // 因为"我们少给你安排了一个点"比"记得提前预约"重要得多，
        // 用户从上往下读，越靠前越该是"这份结果本身的问题"。
        List<String> warnings = new ArrayList<>(notices);
        if (draft != null && draft.warnings() != null) {
            for (String w : draft.warnings()) {
                if (!isBlank(w)) {
                    warnings.add(w.strip());
                }
            }
        }

        return new Assembled(summary, days, warnings);
    }

    /** 把候选回填成一个可以在界面上展示的点 —— **事实全部来自候选，一个字段都不来自模型** */
    private static TravelSpot toSpot(PoiCandidate poi, String time, String tips) {
        return new TravelSpot(
                time,
                poi.name(),
                tips,
                poi.poiId(),
                poi.priceYuan(),
                poi.bookUrl(),
                poi.imageUrl(),
                poi.lat(),
                poi.lng()
        );
    }

    /* ============================================================
     * 住宿：每晚住哪
     * ============================================================ */

    /**
     * 模型对"今晚住哪"的回答被归到了哪一档。
     * 每一档的产品含义都不一样，所以不能只用一个 null / 非 null 来表达 ——
     * "明确说不住"和"没回答"如果混成一个 null，我们就再也分不清该不该兜底了。
     */
    private enum StaySource {
        /** 模型明确给了一个能查到的 H 编号 */
        EXPLICIT,
        /** 模型没回答 → 沿用前一晚那家（这就是"连住"） */
        INHERITED,
        /** 模型没回答、也没有可沿用的 → 从候选池里补一家 */
        FILLED,
        /** 模型明确说了不住，或最后一天没给明确回答（按当天返程处理） */
        NONE,
        /** 这次根本没有酒店数据（needHotel=false，或飞猪没返回） */
        POOL_EMPTY
    }

    private record StayDecision(HotelCandidate hotel, String tips, StaySource source) {
    }

    /**
     * 决定某一晚住哪。
     *
     * 分支顺序是有讲究的，从上往下依次是：没有数据 → 明确说不住 → 明确指定 →
     * 最后一天 → 沿用前一晚 → 补一家。**把"明确说的"排在"兜底的"前面**是这里唯一的原则：
     * 一旦让兜底逻辑有可能覆盖模型的明确回答，"写成 none 却被补了一家酒店"这种
     * 用户看得见、却完全无法理解的错误就会出现。
     */
    private static StayDecision decideStay(String rawCode, String rawTips,
                                           Map<String, HotelCandidate> byCode,
                                           List<HotelCandidate> pool,
                                           HotelCandidate lastStay,
                                           boolean lastDay) {
        if (pool.isEmpty()) {
            return new StayDecision(null, null, StaySource.POOL_EMPTY);
        }
        if (CandidateCodes.isStayNone(rawCode)) {
            // 模型明确说了不住。**这种情况绝不能兜底补一家** ——
            // "明确说不"和"漏了"在产品上差别极大（前者是当天返程、或用户自己订好了），
            // 这正是我们要把 none 定义成一个显式词的原因。
            return new StayDecision(null, null, StaySource.NONE);
        }
        if (rawCode != null && !rawCode.isBlank()) {
            // 大小写归一化在这里做（模型可能写小写 h1）。
            // 不归一的话，"h1 查不到"会被当成模型的幻觉，而其实只是大小写 —— 一条假告警。
            HotelCandidate h = byCode.get(rawCode.strip().toUpperCase(Locale.ROOT));
            if (h != null) {
                return new StayDecision(h, blankToNull(rawTips), StaySource.EXPLICIT);
            }
            // 掉到这里说明编号是编的 → 当作"没回答"走兜底（上面 isUnknownHotelCode 已经记过账）
        }
        if (lastDay) {
            // 最后一天的"没回答"按**当天返程**处理，不沿用前一晚。
            // 这是个有偏向的默认，理由必须写清楚：
            //   多给一晚（"你以为要住 3 晚，其实 2 晚就够"）→ 用户照它去订房，**多花一笔钱**；
            //   少给一晚（"最后一天没写住哪"）→ 用户一眼看得出来，补问一句就行。
            // 两个方向的错代价不对称时，默认要偏向代价小的那一边。
            return new StayDecision(null, null, StaySource.NONE);
        }
        if (lastStay != null) {
            // 连住。**这一档刻意不报警告**：
            // 模型只写第 1 天、后面几天留空，本来就是"同一家住到底"的正常表达方式，
            // 我们替它说明白是补救，不是它犯错。每晚都报一句"已沿用昨天的酒店"，
            // 只会让用户以为出了什么问题。
            return new StayDecision(lastStay, null, StaySource.INHERITED);
        }
        // 第一晚就没有答案 → 从池子里补第一家。
        // 为什么是"第一家"而不是"评分最高的那家"：池子的顺序就是飞猪给的相关性顺序，
        // 和挑景点用的是同一条规则（相关性优先）。一致比微优化重要 ——
        // 而且真要按评分挑，那应该由规则表来决定，不该在这里偷偷加一条排序。
        return new StayDecision(pool.get(0), null, StaySource.FILLED);
    }

    /** 模型写了一个"看起来像住宿编号、但清单里没有"的值？ —— 和"没回答"的处理一样，但必须记账 */
    private static boolean isUnknownHotelCode(String rawCode, List<HotelCandidate> pool,
                                              Map<String, HotelCandidate> byCode) {
        if (pool.isEmpty() || rawCode == null || rawCode.isBlank()
                || CandidateCodes.isStayNone(rawCode)) {
            // 池子空着的时候，模型无论写什么都查不到 —— 那不算它的错，别记账。
            // 这个判断很重要：少了它，needHotel=false 的那次生成会凭空多出一条"模型编了编号"的告警。
            return false;
        }
        return !byCode.containsKey(rawCode.strip().toUpperCase(Locale.ROOT));
    }

    /** 把酒店候选回填成一个"今晚住这儿"。事实全部来自候选，只有 tips 来自模型。 */
    private static HotelStay toStay(HotelCandidate h, String tips) {
        return new HotelStay(
                h.name(),
                h.hotelId(),
                h.priceYuan(),
                h.star(),
                h.rating(),
                h.address(),
                h.lat(),
                h.lng(),
                h.bookUrl(),
                h.imageUrl(),
                tips
        );
    }

    private static String blankToNull(String s) {
        return isBlank(s) ? null : s.strip();
    }

    /**
     * 时间格式兜底。
     * 只做"看起来像不像 HH:mm"这一层判断，**不**去纠正 25:99 这种值 ——
     * 那属于排期逻辑（要拿到顺序和距离才算得出来），不该塞进装配器里。
     */
    private static String normalizeTime(String raw, int indexInDay) {
        String fallback = DEFAULT_TIMES[Math.min(indexInDay, DEFAULT_TIMES.length - 1)];
        if (raw == null) {
            return fallback;
        }
        String s = raw.strip();
        return s.matches("\\d{1,2}:\\d{2}") ? s : fallback;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
