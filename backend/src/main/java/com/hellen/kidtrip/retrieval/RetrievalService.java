package com.hellen.kidtrip.retrieval;

import com.hellen.kidtrip.candidate.Candidate;
import com.hellen.kidtrip.candidate.CandidateNormalizer;
import com.hellen.kidtrip.candidate.HotelCandidate;
import com.hellen.kidtrip.candidate.PoiCandidate;
import com.hellen.kidtrip.flyai.CityNames;
import com.hellen.kidtrip.flyai.FlyaiProperties;
import com.hellen.kidtrip.rule.PlanRules;
import com.hellen.kidtrip.rule.RuleChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 景点 / 酒店的检索编排：**决定了整个系统的成本结构**。
 *
 * 这个类的存在理由是一组实测事实（2026-10-13）：
 *   ① 并发 6 个上游调用 → 3 个失败，`HTTP 429 Rate limit exceeded`；
 *   ② 连续串行 6 个不间隔 → 后 3 个失败，`HTTP 451 ... risk control`（风控）；
 *   ③ 冷却 90 秒后，**串行 + 间隔 7 秒**的 4 次调用全部成功 → 是限流窗口，不是封号。
 *   ④ 一次**宽查**（不带 --category）能覆盖 7 个分类、10 条结果，
 *      一次就顶掉原来"3 个分类各查一次"。
 *
 * 所以这里的三条策略不是"最佳实践"，是**在这个上游能不能跑通的条件**：
 *   串行（不是并发）· 留间隔 · 尽量少发。
 *
 * 一句话总结这个类的设计原则：
 *   **凡是能靠本地重算解决的，就绝不花一次上游调用。**
 *   放宽规则 = 本地重算（免费）；补查分类 = 真花额度。优先级由此决定。
 *
 * ⚠️ 那三条策略的**执行**已经不在这个类里了（2026-10-13 抽到 {@link FlyaiCaller}）：
 * 城际车次查询是第二个上游调用方，而"串行"只有在共用一把锁时才成立。
 * 这个类现在只决定"查几次、查什么、怎么记账"。
 */
@Component
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    /** 一轮生成最多补查几个分类 —— 每多一个就多一次调用、多一份被限流的风险 */
    private static final int MAX_SUPPLEMENT_QUERIES = 2;
    /**
     * 上游 `star` 字段里代表"青旅 / 日租公寓"的那一档 —— 我们要挡掉的就是它。
     * 实测这是**唯一**能识别出青旅的字段：那些"青年公寓""客栈"的名字五花八门，
     * 但 star 一律是"经济型"。（`--hotel-types hotel` 挡不住，见 FlyaiProperties 的注释。）
     */
    private static final String BUDGET_TIER = "经济型";

    private final FlyaiCaller caller;
    private final CandidateNormalizer normalizer;
    private final RuleChecker checker;
    private final FlyaiProperties props;

    /**
     * ⚠️ 这里**没有** runner、也没有限流闸门和缓存 —— 它们在 {@link FlyaiCaller} 里。
     *
     * 2026-10-13 抽出去的，原因是城际车次查询也要调上游：
     * 如果那边自己持一把锁，就形成"两把锁各自都对、合起来却允许并发"的局面，
     * 而那正是实测里唯一会招来 429 的形态。抽成一个共享 bean 之后，
     * "所有上游调用串行"从约定变成了**构造上不可能违反**。
     */
    public RetrievalService(FlyaiCaller caller, CandidateNormalizer normalizer,
                            RuleChecker checker, FlyaiProperties props) {
        this.caller = caller;
        this.normalizer = normalizer;
        this.checker = checker;
        this.props = props;
    }

    public RetrievalResult retrieve(RetrievalRequest request) {
        long start = System.currentTimeMillis();
        RetrievalRequest req = normalize(request);

        PlanRules rules = PlanRules.DEFAULT;
        if (req.maxTicketPriceYuan() != null) {
            rules = rules.withMaxTicketPriceYuan(req.maxTicketPriceYuan());
        }
        Deadline deadline = Deadline.after(rules.budgetMs());

        Run run = new Run();

        // ── 第一轮：宽查 ──────────────────────────────────────────────
        // 为什么不带 --category：实测一次宽查就覆盖 7 个分类（含动物园、海洋馆、主题乐园），
        // 而按分类查要发 3 次。少两次调用，就少两次被 429 的机会。
        collectPois(poiArgs(req, null), run, deadline);
        if (req.needHotel()) {
            fetchHotels(req, run, deadline);
        }

        RuleChecker.CheckResult check = checker.check(list(run.pois), list(run.hotels), rules);
        int rounds = 1;

        // ── 第二轮：定向补查（只在"池子里真的没有"时才花额度）───────────
        // 注意这里有两个 missing：missingBefore 用来**决定要不要补查**，
        // missingAfter 用来**告诉用户最终还缺什么**。混用一个会闹笑话 ——
        // 补查成功之后还跟用户说"没找到动物园"，是比不提示更糟的体验。
        List<String> missingBefore = missingCategories(req.wantedCategories(), check.pois());
        int needed = neededSpots(req, rules);
        boolean supplemented = false;
        if (shouldSupplement(missingBefore, check.pois().size(), needed, rounds, rules, deadline)) {
            for (String category : supplementTargets(missingBefore, req.wantedCategories())) {
                if (deadline.expired()) {
                    run.warnings.add("时间预算用完了，后续补查已跳过");
                    break;
                }
                collectPois(poiArgs(req, category), run, deadline);
            }
            rounds = 2;
            supplemented = true;
            check = checker.check(list(run.pois), list(run.hotels), rules);
        }

        // ── 放宽规则（**不花额度**，所以放在补查之后，且不看预算）─────────
        // 顺序不能反：先做免费的（放宽），再做花钱的（补查），才是省钱的顺序。
        // 这里放在补查之后，是因为放宽只对"已经在池子里但被规则杀掉"的那些有用；
        // 池子里没有的东西，放宽多少遍也变不出来。
        boolean relaxed = false;
        if (check.pois().size() < needed && rules.canRelax()) {
            PlanRules loosened = rules.relaxed();
            RuleChecker.CheckResult recheck = checker.check(list(run.pois), list(run.hotels), loosened);
            if (recheck.pois().size() > check.pois().size()) {
                relaxed = true;
                rules = loosened;
                check = recheck;
            }
        }

        // ── 记账：把"不够"说清楚，而不是悄悄给一份残缺的行程 ─────────────
        List<String> missingAfter = missingCategories(req.wantedCategories(), check.pois());
        if (check.pois().isEmpty()) {
            // 2026-10-13 改：原来只写"X 没有查到任何可用的景点"，用户读完不知道下一步干什么。
            // 而现在最常见的那种"查不到"，原因恰恰是他给的地名不是个城市（实测"广东周边"）——
            // 一句不带出路的提示，会让他在同一个坑里再试一次。
            // 保险起见两种可能都提：城市名不对 / 那个城市确实没数据。
            run.warnings.add(req.city() + "没有查到任何可用的景点，行程无法排出来。"
                    + "请确认这是一个**具体城市**（「广东周边」这类区域说法飞猪查不到），"
                    + "也可以换「珠海」「广州」这类城市再试一次");
        } else if (check.pois().size() < needed) {
            run.warnings.add("可用景点只有 " + check.pois().size() + " 个，少于 "
                    + req.days() + " 天 × " + rules.maxSpotsPerDay() + " 个/天 = " + needed + " 个，行程会偏空");
        }
        if (req.needHotel() && check.hotels().isEmpty()) {
            run.warnings.add("没有查到可用的酒店");
        }
        // 哨兵：万一星级筛**失效了**（上游改了行为、参数被忽略），结果里会重新冒出一堆"经济型"。
        // 这种"静默走偏"最难发现 —— 价格照样有、名字照样有、行程照样排得出来，只是排出来的是青旅。
        // 只在"我们确实带了筛"时才看：用户自己把筛关掉的，就别拿这条去烦他。
        // （空池由 warnIfBudgetHeavy 自己挡住，这里不重复判 —— 判两次早晚会有一处忘了改。）
        if (req.needHotel() && hotelStars() != null) {
            warnIfBudgetHeavy(check.hotels(), run);
        }
        if (!missingAfter.isEmpty()) {
            run.warnings.add("画像期望的 " + String.join("、", missingAfter) + " 在飞猪上没有找到"
                    + (supplemented ? "（已经补查过一轮）" : ""));
        }
        if (run.parseFailed > 0) {
            run.warnings.add("有 " + run.parseFailed + " 条数据解析失败已跳过（可能是飞猪改了字段格式）");
        }

        List<Candidate> candidates = new ArrayList<>();
        candidates.addAll(check.pois());
        candidates.addAll(check.hotels());

        RetrievalResult.Provenance provenance = new RetrievalResult.Provenance(
                req,
                rules,
                List.copyOf(run.queries),
                rounds,
                run.found,
                check.kept(),
                check.rejected(),
                relaxed,
                missingAfter,
                run.warnings,
                run.upstreamCalls,
                run.cacheHits,
                System.currentTimeMillis() - start
        );
        return new RetrievalResult(candidates, provenance);
    }

    /* ============================================================
     * 查询条件的构造
     * ============================================================ */

    /** 宽查：只有 city，**没有 category**。补查时才有 category。 */
    private static List<String> poiArgs(RetrievalRequest req, String category) {
        List<String> args = new ArrayList<>(List.of("search-poi", "--city-name", req.city()));
        if (category != null && !category.isBlank()) {
            args.add("--category");
            args.add(category);
        }
        // 刻意**不传 --keyword**：实测它是窄筛 —— `--keyword 亲子` 只回 5 条
        // （全是室内儿童乐园）且**票价 0 条**，一用就把宽查攒下的池子毁掉。
        // RetrievalPlan.keywords 因此只作为"意图记录"，不参与检索。
        return args;
    }

    /**
     * 搜酒店的参数。
     *
     * `hotelStars` 由调用方决定传不传（见 {@link #hotelStars()}）——
     * 这里不自己去读配置，是为了让"带筛"和"不带筛"两条路径在输出上**看起来就是两个不同的查询**，
     * 溯源面板里能一眼看出这次到底放宽了没有。
     */
    private static List<String> hotelArgs(RetrievalRequest req, String hotelStars) {
        List<String> args = new ArrayList<>(List.of("search-hotel", "--dest-name", req.city()));
        // 带日期，价格才是"你要去的那几天"的价格；不带也能查，但那是参考价，拿来算预算会不准。
        // ⚠️ 日期还有个不明显的副作用：**不带日期时上游不回传 star**（档次那一栏全是 null）。
        //    既然我们的星级筛选要用 star 做二次判断，日期就从"价格准不准"升级成了"字段有没有"。
        // 刻意**不传 --poi-name**：实测传了不匹配的景点名时它会被静默忽略，
        // 返回一批无关酒店却不报错 —— 这种"静默走偏"最难发现，所以干脆不用它。
        if (req.startDate() != null) {
            args.add("--check-in-date");
            args.add(req.startDate());
            args.add("--check-out-date");
            args.add(LocalDate.parse(req.startDate()).plusDays(req.days()).toString());
        }
        // 星级筛放最后，让日志里的顺序读起来是"目的地 → 日期 → 星级"，和人想问题的顺序一致。
        if (hotelStars != null) {
            args.add("--hotel-stars");
            args.add(hotelStars);
        }
        return args;
    }

    /** 要发给上游的星级筛；null = 不筛。把"配置里的空串"翻译成"这里传 null"，只在这一处做。 */
    private String hotelStars() {
        String s = props.hotelStars();
        return s == null || s.isBlank() ? null : s;
    }

    /* ============================================================
     * 采集
     * ============================================================ */

    private void collectPois(List<String> args, Run run, Deadline deadline) {
        var normalized = normalizer.pois(call(args, run, deadline));
        run.found += normalized.items().size();
        run.parseFailed += normalized.failed();
        for (PoiCandidate p : normalized.items()) {
            run.pois.putIfAbsent(keyOf(p.stableId(), p.getKind(), p.name()), p);
        }
    }

    /**
     * 采酒店：带星级筛，但**筛空了要能退回去**。
     *
     * 星级筛是我们替用户加的一层"品味"（带娃住得像样点），它有一个必然的坏情况：
     * 小城市 / 特殊日期可能一家高档酒店都没有。那时上游返回 0 条 ——
     * 如果我们不管，用户会看到"没有查到可用的酒店"，而实际上是**有的**，
     * 只是被我们自己加的筛选挡住了。**加了筛选，就得自己兜住筛选的后果。**
     *
     * 兜底方式是"去掉筛再查一次"，代价是多花一次上游调用。
     * 但它只在"我们把自己的结果清空了"这条罕见路径上触发（深圳/桂林实测都不触发），
     * 所以是划算的保险 —— 而且它是**降级**，不是"再试一次同样的查询"：
     * 换的是参数，拿回来的必然是不同的集合，不存在白等。
     */
    private void fetchHotels(RetrievalRequest req, Run run, Deadline deadline) {
        String stars = hotelStars();
        collectHotels(hotelArgs(req, stars), run, deadline);
        if (stars != null && run.hotels.isEmpty() && !deadline.expired()) {
            run.warnings.add("按 " + stars + " 星筛选没有找到酒店，已放宽筛选条件重新查找");
            collectHotels(hotelArgs(req, null), run, deadline);
        }
    }

    private void collectHotels(List<String> args, Run run, Deadline deadline) {
        var normalized = normalizer.hotels(call(args, run, deadline));
        run.found += normalized.items().size();
        run.parseFailed += normalized.failed();
        for (HotelCandidate h : normalized.items()) {
            run.hotels.putIfAbsent(keyOf(h.stableId(), h.getKind(), h.name()), h);
        }
    }

    /**
     * 跑一次 CLI，并把这次调用记到本轮的账上。
     *
     * 注意 `upstreamCalls` 与 `queries` 是两个不同的计数器，而且它们**故意**不一样：
     *   queries 记的是"我们要了什么"（含缓存命中的），给用户看"这次规划查了哪些条件"；
     *   upstreamCalls 记的是"真正花了多少额度"（缓存命中的不算），
     *   因为限额是按**真实请求数**算的，不是按"我们想查几次"算的。
     * 这两个数字在限流面前必须分开，混起来会让你以为还有余量。
     *
     * 退避、间隔、缓存本身都不在这里 —— 在 {@link FlyaiCaller}。
     * 这个方法现在只剩"记账"，因为它算的是**本轮的账**，而调用方不知道账本长什么样。
     */
    private String call(List<String> args, Run run, Deadline deadline) {
        run.queries.add("flyai " + String.join(" ", args));

        FlyaiCaller.Result result = caller.call(args, deadline);
        if (result.fromCache()) {
            run.cacheHits++;
        } else {
            run.upstreamCalls++;
        }
        return result.raw();
    }

    /* ============================================================
     * 决策：要不要补查
     * ============================================================ */

    /**
     * 星级筛的哨兵：经济型的占比过半，说明筛选可能没生效，告诉用户一声。
     *
     * 这里**只记账、不淘汰** —— 和景点那套"不合格就丢掉"正好相反。
     * 理由是不对称的：景点是我们自己去挑的，挑错了就该丢；
     * 酒店这一层真正的防线在上游（`--hotel-stars`，实测深圳 10 条不合格 → 9 条全部合格），
     * 在这里再淘汰一轮就是**双重淘汰**，一旦上游哪天把 star 换成别的词、
     * 我们本地这个"经济型"判据失配，就会把 10 家酒店全筛没 —— 用户看到"没查到酒店"，
     * 而真实原因是我们自己的过滤器失配。宁可给一份"可能不太合适但真实存在"的酒店 + 一句提醒，
     * 也不要给一份空的、还说不清为什么空的结果。
     *
     * 包级可见（不是 private）：同包的测试要能直接调它 —— 见 RetrievalServiceTest。
     */
    static void warnIfBudgetHeavy(List<HotelCandidate> hotels, Run run) {
        // 空池直接退出。不挡的话 `0 * 2 >= 0` 成立，会报一句
        // "查到的 0 家酒店里有 0 家是经济型" —— 荒谬到没人会去猜它是从哪来的。
        // （这正是这类"只改记账"的方法最该被测到的边界：它不影响主流程，所以永远不会有人发现它报错了。）
        if (hotels.isEmpty()) {
            return;
        }
        long budget = hotels.stream().filter(h -> BUDGET_TIER.equals(h.star())).count();
        if (budget * 2 >= hotels.size()) {
            run.warnings.add("查到的 " + hotels.size() + " 家酒店里有 " + budget
                    + " 家是经济型（青旅 / 日租公寓居多），可能不适合亲子入住，建议逐家确认");
        }
    }

    /**
     * 补查的触发条件 —— 只认两种情况：
     *  ① **画像期望的类别一条都没出现**（比如画像说"爱动物"，池子里却没有动物园）；
     *  ② 数量不够（少于 days × maxSpotsPerDay）。
     *
     * 注意这里没有"飞猪返回条数少就重查"这种判断。返回条数少本身不说明问题 ——
     * 深圳宽查就 10 条，够排 3 天；盲目重查只会烧额度换来同一批数据。
     */
    private static boolean shouldSupplement(List<String> missing, int keptPois, int needed,
                                            int rounds, PlanRules rules, Deadline deadline) {
        if (rounds >= rules.maxRounds() || deadline.expired()) {
            return false;
        }
        return !missing.isEmpty() || keptPois < needed;
    }

    /**
     * 补查谁、按什么顺序 —— 这直接决定花掉几次额度，所以规则要明确：
     *  1. **缺失的类别优先**（那是"真的没有"，补了最可能有效）；
     *  2. 数量不够时，再拿期望类别里**还没查过**的去补；
     *  3. 最多 {@value MAX_SUPPLEMENT_QUERIES} 个。顺序由两个 for 的先后保证，去重靠 seen。
     */
    private static List<String> supplementTargets(List<String> missing, List<String> wanted) {
        Set<String> seen = new HashSet<>();
        List<String> targets = new ArrayList<>();
        for (String c : missing) {
            if (c != null && !c.isBlank() && seen.add(c)) {
                targets.add(c);
            }
        }
        for (String c : wanted) {
            if (c != null && !c.isBlank() && seen.add(c)) {
                targets.add(c);
            }
        }
        return targets.subList(0, Math.min(MAX_SUPPLEMENT_QUERIES, targets.size()));
    }

    /**
     * 期望的类别里，哪些**一条都没出现**。
     *
     * 这是 `RetrievalPlan.wantedCategories` 语义变化后的全部用法 ——
     * 它不再是"要发的查询命令"，而是"覆盖度的检查表"。这个区别很实际：
     * 前者让模型间接决定了我们发几次请求（而它不知道有限流），后者把决定权留在有成本信息的一侧。
     */
    private static List<String> missingCategories(List<String> wanted, List<PoiCandidate> kept) {
        Set<String> present = new HashSet<>();
        for (PoiCandidate p : kept) {
            if (p.category() != null) {
                present.add(p.category().strip());
            }
        }
        List<String> missing = new ArrayList<>();
        for (String c : wanted) {
            if (c != null && !c.isBlank() && !present.contains(c.strip())) {
                missing.add(c.strip());
            }
        }
        return missing;
    }

    private static int neededSpots(RetrievalRequest req, PlanRules rules) {
        return req.days() * rules.maxSpotsPerDay();
    }

    /* ============================================================
     * 杂项
     * ============================================================ */

    /** 把 null 挡在入口：之后全程只用补齐后的对象，下游不用再判一次 null。 */
    private static RetrievalRequest normalize(RetrievalRequest in) {
        if (in == null || in.city() == null || in.city().isBlank()) {
            throw new IllegalArgumentException("city 不能为空 —— 不知道查哪座城市，检索无从开始");
        }
        // ⚠️ 这一道必须留在**发请求之前**，因为上游不会替我们报错：
        //    实测 --city-name 传"广东周边"和传"asdfgh"得到的都是 { data: null, status: 0, message: "empty" }
        //    —— 和"这个地方真的没数据"完全一样。放过去的结果是：白花一次额度，
        //    然后用户看到一句误导的"没查到可用的景点"，永远不知道原因是城市名写错了。
        //    校验放在最靠近"花钱的地方"，是为了让它在花钱**之前**生效。
        if (!CityNames.isSingleCity(in.city())) {
            throw new IllegalArgumentException(
                    "「" + in.city().strip() + "」不是一个具体的城市名。" + CityNames.hint());
        }
        return new RetrievalRequest(
                in.city().strip(),
                blankToNull(in.startDate()),
                in.days() == null || in.days() <= 0 ? 3 : in.days(),
                in.wantedCategories() == null ? List.of() : in.wantedCategories(),
                in.keywords() == null ? List.of() : in.keywords(),
                in.needHotel() != null && in.needHotel(),
                in.maxTicketPriceYuan()
        );
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String keyOf(String stableId, String kind, String name) {
        return stableId == null || stableId.isBlank() ? kind + ":" + name : stableId;
    }

    private static <T> List<T> list(Map<String, T> m) {
        return new ArrayList<>(m.values());
    }

    /** 一次检索过程中累积的可变状态。抽出来是为了让主流程读起来是一条线，而不是一堆零散变量。
     *
     *  包级可见（不是 private）：为了让**同包的测试**能直接构造它、直接调下面的决策方法。
     *  这类"只改记账、不改数据"的逻辑最容易写完就再没人跑过 —— 而它偏偏是出问题时唯一的信号源。 */
    static final class Run {
        final List<String> queries = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        /** 用 Map 而不是 List：宽查和补查回来同一条景点时天然去重，不用再想一遍 */
        final Map<String, PoiCandidate> pois = new LinkedHashMap<>();
        final Map<String, HotelCandidate> hotels = new LinkedHashMap<>();
        int found;
        int parseFailed;
        int upstreamCalls;
        int cacheHits;
    }
}
