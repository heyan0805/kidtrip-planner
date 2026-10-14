package com.hellen.kidtrip.plan;

import com.hellen.kidtrip.candidate.Candidate;
import com.hellen.kidtrip.candidate.HotelCandidate;
import com.hellen.kidtrip.candidate.PoiCandidate;
import com.hellen.kidtrip.flyai.FlyaiException;
import com.hellen.kidtrip.llm.PlanComposer;
import com.hellen.kidtrip.llm.PlanDraft;
import com.hellen.kidtrip.llm.RetrievalPlanner;
import com.hellen.kidtrip.retrieval.LegService;
import com.hellen.kidtrip.retrieval.RetrievalRequest;
import com.hellen.kidtrip.retrieval.RetrievalResult;
import com.hellen.kidtrip.retrieval.RetrievalService;
import com.hellen.kidtrip.retrieval.Transfer;
import com.hellen.kidtrip.retrieval.TransportMode;
import com.hellen.kidtrip.rule.PlanRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编排：把六段串成一条线，产出最终那份行程。
 *
 *   画像 → 检索条件（B5：模型规划一次）→ 检索（宽查 + 补查 + 规则校验）
 *        → 候选池 → 让模型从真实候选里挑与排 → 回填 → 溯源
 *
 * 这个类的职责边界要守住：**它只做"串联"和"换算"，不做任何判断。**
 *   要查几次上游 —— 是 RetrievalService 的决定（它才知道限流）
 *   什么叫合格   —— 是 RuleChecker 的决定
 *   查哪几类     —— 是 RetrievalPlanner 的决定
 *   怎么挑怎么排 —— 是 PlanComposer + PlanAssembler 的决定
 * 编排层一旦开始自己"顺手判一下"，这些决定就会散落到两个地方，
 * 以后改规则要改两处，而且总有一处会被漏掉。
 */
@Service
public class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);

    /**
     * 送进 prompt 的候选上限。
     *
     * 为什么要截：候选清单是**逐行**塞进 prompt 的，每条大约 40~50 个 token。
     * 池子里要是有 100 条，光清单就 5000 token —— 又慢又贵，而且模型在超长清单里
     * 挑东西的质量反而会下降（注意力被稀释）。
     *
     * 为什么取 30：排一份 6 天的行程最多需要 6×4=24 个点，30 条能留出被截断的余量。
     * 而且池子里的顺序是"飞猪认为更相关的先回来"，截前面就是按相关性取。
     */
    private static final int MAX_CANDIDATES_IN_PROMPT = 30;

    /**
     * 送进 prompt 的酒店上限。
     *
     * 为什么比景点少这么多（20 vs 30）：一天只要一家酒店，一份 6 天的行程最多用到 6 家，
     * 而"少换酒店"意味着实际用到的更少（常常 1~2 家）。
     * 而且酒店那行比景点行更长（多了星级和评分），token 账更算不过来。
     *
     * ⚠️ 2026-10-14 从 10 提到 20。**这不是"顺手放宽"，是改成两档查询之后必须跟着改的**：
     *    上游一次只回一档，默认配置 `"3;4"` 会查两次、池子大约 20 家。
     *    而下面的截断是 `subList(0, MAX)` —— 按池子的插入顺序取前 N。
     *    上限还是 10 的话，**第二个档位一家都进不来**，等于白花一次调用，
     *    而且是从界面上完全看不出来的那种白花（候选照样有、行程照样排）。
     */
    private static final int MAX_HOTELS_IN_PROMPT = 20;

    /**
     * 每天给用户看几家"别的酒店"。
     *
     * 为什么是 3：和机票那边（每天最多一条备选）不同，酒店的可比维度少
     * （就是价格 + 档次 + 离景点远近），3 家足够画出一条"便宜/居中/好一点"的梯度。
     * 再多就变成一份酒店列表，而用户要的是"今晚住哪儿"这一个决定。
     */
    private static final int MAX_HOTEL_ALTERNATIVES = 3;

    private final RetrievalService retrievalService;
    private final PlanComposer composer;
    private final RetrievalPlanner retrievalPlanner;
    private final LegService legService;
    private final GenerationProgress progress;

    public PlanService(RetrievalService retrievalService, PlanComposer composer,
                       RetrievalPlanner retrievalPlanner, LegService legService,
                       GenerationProgress progress) {
        this.retrievalService = retrievalService;
        this.composer = composer;
        this.retrievalPlanner = retrievalPlanner;
        this.legService = legService;
        this.progress = progress;
    }

    /**
     * 不带进度登记的入口。
     *
     * 保留它不是为了兼容旧调用 —— 而是因为**"想看进度"必须是调用方主动要求的**。
     * 脚本、单测、排障时直接调这个方法，不必为了跑通先造一个 runId。
     */
    public TravelPlan generate(PlanGenerateRequest request) {
        return generate(request, null);
    }

    /**
     * 带进度登记的入口。
     *
     * 就三件事：登记开始 → 干活 → 记账。真正的编排在 {@link #doGenerate} 里，一个字没动。
     *
     * 为什么"记失败"放在这一层，而不是散在 doGenerate 的每个 throw 前面：
     * 一趟生成能从很多地方失败（模型超时、上游异常、参数不合法……），
     * 逐个 throw 去补一行 finish 是一次注定会漏的活 —— 今天补了五处，
     * 明天新加第六条失败路径就又漏了，而漏掉的表现是"进度条永远停在那一步转圈"。
     * 这里只有**一个出口**，漏不掉。**能用一个 try 兜住的账，不要用五处手写。**
     */
    public TravelPlan generate(PlanGenerateRequest request, String runId) {
        progress.begin(runId);
        try {
            TravelPlan plan = doGenerate(request, runId);
            progress.finish(runId, true);
            return plan;
        } catch (RuntimeException e) {
            progress.finish(runId, false);
            throw e;
        }
    }

    /**
     * 真正的编排流程。
     *
     * ⚠️ `runId` 在这一段里**只以 `progress.mark(runId, …)` 的形式出现**，
     *    不参与任何一个判断、不进任何一份返回值、不影响任何一次上游调用。
     *    这不是随手写成的，是硬约束：打点是**旁路**。
     *    哪天有人让流程"根据进度做点什么"（比如"已经查得够久了，跳过补查吧"），
     *    这条线就断了 —— 那时它不再是观测，而是会改变结果的东西，
     *    而"进度显示的是真实发生的事"这句话也就跟着作废了。
     */
    private TravelPlan doGenerate(PlanGenerateRequest request, String runId) {
        long start = System.currentTimeMillis();
        List<String> warnings = new ArrayList<>();

        TravelParams params = normalizeTripDate(request.paramsOrEmpty(), warnings).orDefaults();

        if (params.city() == null || params.city().isBlank()) {
            throw new IllegalArgumentException("city 不能为空 —— 不知道去哪座城市，检索和排期都无从开始");
        }

        // ── 城际交通：**先查**（B7）────────────────────────────────────────
        // 这是这一轮唯一一处顺序改动，而且它改的是一条**原来就想错了**的判断：
        //   原来的注释说"车次的产物只是'每天怎么到的'那一行，它不影响模型挑哪些景点"。
        //   实测说明它影响 —— 而且影响的是最要紧的两天：第 1 天几点能开始、最后一天几点必须收摊。
        //   撞出来的那份行程里，第 1 天排了 10:00 的景点（可车 10:39 才到），
        //   最后一天排了 15:00 的活动（可返程车 06:18 就走了）。两天都不可能成立。
        // 也不会多花时间：车次和检索共用**同一把限流锁**，本来就是串行的，
        //   谁先谁后不影响总时长；真正决定耗时的是后面的模型调用，它仍在两者之后。
        // 更不会多花钱：结果进缓存，演示当天这两条查询是命中的。
        //
        // ⚠️ 车次的告警单独收着，最后才并进 warnings —— 见下面 addAll 处的说明。
        progress.mark(runId, GenerationProgress.Stage.LEGS);
        List<String> legQueries = new ArrayList<>();
        List<String> legWarnings = new ArrayList<>();
        Legs legs = searchLegs(params, legQueries, legWarnings);

        // ── 检索条件：给了就用，没给就让模型规划一次（B5）────────────────────
        // 这就是"检索条件是模型受控输出一次"这句话的落点。
        // 为什么放在这一步而不是 /chat 里：因为**它决定我们要发几次上游请求**，
        // 而"有几次成本"这个信息，只有准备去查的那一刻才完整（用户的画像可能刚改过）。
        // 决定权留在知道成本的一侧 —— 这和"wantedCategories 是期望不是命令"是同一条原则。
        // ⚠️ 打点放在 if **之前**，而不是模型那个分支里面：
        //    调用方显式给了检索条件时，这一步不调模型（瞬间完成），但它**仍然是一次真实的经过**。
        //    放进 else 分支里的话，脚本调用（给了条件）的进度会永远缺掉这一格。
        progress.mark(runId, GenerationProgress.Stage.PLAN_QUERIES);
        RetrievalPlan retrievalPlan;
        if (request.retrieval() != null) {
            // 调用方（脚本、排障、以后可能的前端）显式给了条件 —— 照用，不去问模型。
            // 这条路径必须留：否则"验证检索逻辑"这件事就永远要先花一次 LLM 调用。
            retrievalPlan = request.retrieval().orDefaults();
            log.info("使用调用方给定的检索条件：{}", retrievalPlan.wantedCategories());
        } else {
            RetrievalPlanner.Planned planned = retrievalPlanner.plan(params);
            retrievalPlan = planned.plan();
            // 规划过程中的实话（比如"模型写的类别里有飞猪不认的，已忽略"）排在最前面 ——
            // 它解释的是"这次为什么是这么查的"，比后面所有的告警都更靠前因。
            warnings.addAll(planned.notes());
        }

        // ── 前三段：检索（内部含规范化、规则校验、补查与放宽）────────────
        progress.mark(runId, GenerationProgress.Stage.SEARCH);
        RetrievalResult pool = retrievalService.retrieve(new RetrievalRequest(
                params.city(),
                params.startDate(),
                params.days(),
                retrievalPlan.wantedCategories(),
                retrievalPlan.keywords(),
                retrievalPlan.needHotel(),
                request.maxTicketPriceYuan()
        ));

        // ── 分流：景点和酒店拆成两个池子 ─────────────────────────────────
        // 它们在契约的**两个不同位置**落地（TravelDay.spots 和 TravelDay.hotel），
        // 所以从这一步起就分开走，后面每个环节都按"两个清单"处理：
        // 两个编号空间（C / H）、两条装配逻辑（景点去重、酒店允许重复）。
        // 混在一起走会出的事很具体：酒店被排进上午 9:30 的景点列表里，而且全程不报错。
        List<PoiCandidate> pois = new ArrayList<>();
        List<HotelCandidate> hotels = new ArrayList<>();
        for (Candidate c : pool.candidates()) {
            if (c instanceof PoiCandidate p) {
                pois.add(p);
            } else if (c instanceof HotelCandidate h) {
                hotels.add(h);
            }
        }
        if (pois.size() > MAX_CANDIDATES_IN_PROMPT) {
            log.info("候选景点 {} 条，超出 prompt 上限 {}，按飞猪的相关性顺序取前 {} 条",
                    pois.size(), MAX_CANDIDATES_IN_PROMPT, MAX_CANDIDATES_IN_PROMPT);
            pois = new ArrayList<>(pois.subList(0, MAX_CANDIDATES_IN_PROMPT));
        }
        if (hotels.size() > MAX_HOTELS_IN_PROMPT) {
            log.info("候选酒店 {} 家，超出 prompt 上限 {}，按飞猪的相关性顺序取前 {} 家",
                    hotels.size(), MAX_HOTELS_IN_PROMPT, MAX_HOTELS_IN_PROMPT);
            hotels = new ArrayList<>(hotels.subList(0, MAX_HOTELS_IN_PROMPT));
        }

        PlanRules rules = pool.provenance().rules();
        // warnings 在上面就已经建好了（检索条件的规划说明要排在第一条），这里只追加检索层的告警
        warnings.addAll(pool.provenance().warnings());

        // ── 备选池：这一轮查到的全部合格景点，交给界面做「换一个」（B9）────────
        // ⚠️ 它是从 pool.candidates() **直接**投影的，不是从上面的 pois。
        //    因为 pois 再过几行就会被截到 30 条 —— 而那是 prompt 的成本限制，
        //    不是"用户能选几条"的限制：**"给模型看多少"和"给用户多少可选"是两件事。**
        //    让它们共用一个变量，以后调 prompt 上限时就会顺手把用户的选择也砍掉，
        //    而且不报错 —— 用户只会觉得"可换的怎么这么少"。
        //    实测这次广州查到 41 个合格景点，模型看了 30 个，用户能看到全部 41 个。
        List<SpotOption> spotPool = spotPoolOf(pool.candidates());
        log.info("备选池：{} 条（其中 {} 条送进了 prompt）", spotPool.size(), Math.min(pois.size(), MAX_CANDIDATES_IN_PROMPT));

        // ── 第五、六段：让模型挑与排，然后回填事实 ──────────────────────
        // 同样打在 if 之前：候选池为空时会跳过模型调用，那一步也就不再耗时，
        // 但它依然"发生过"（我们确实走到了这里，并做出了不再问模型的决定）。
        progress.mark(runId, GenerationProgress.Stage.COMPOSE);
        PlanAssembler.Assembled assembled;
        if (pois.isEmpty()) {
            // 池子空的时候**不调模型**。理由不只是省钱：
            // 给模型一份空清单，它除了编景点没有别的选择 —— 而我们整轮设计就是为了不让它编。
            // 不调用它，就没有"它会编"这个问题。
            log.info("候选池为空，跳过模型调用");
            assembled = new PlanAssembler.Assembled(
                    "没能在" + params.city() + "找到可用的景点，这份行程暂时排不出来。",
                    List.of(),
                    List.of());
        } else {
            PlanDraft draft = composer.draft(params, pois, hotels, rules, legs);
            assembled = PlanAssembler.assemble(draft, pois, hotels, params, rules);
        }

        warnings.addAll(assembled.warnings());

        // ── 把先查到的两段交通挂到第一天和最后一天上（B7）────────────────────
        // 查在排程之前（见上面的说明），挂在排程之后 —— 因为"挂到哪一天"这件事
        // 得等每天排出来才知道。两步分开，是因为它们依赖的东西不同：
        // 查只依赖画像，挂依赖装配后的天数。
        progress.mark(runId, GenerationProgress.Stage.VALIDATE);
        List<TravelDay> days = attachLegs(assembled.days(), legs);

        // ── 住宿备选：今晚还能住哪儿（2026-10-14）─────────────────────────
        // 和上面的交通是同一类的"后处理"：都发生在装配之后，因为都需要装配层拿不到的东西
        // （交通要真实车次，备选要**未截断**的完整酒店池）。
        // 它排在 attachLegs 后面而不是前面，只是因为读起来顺 —— 交通讲"怎么到"，
        // 备选讲"住哪儿"，正好是一天的两个落点。
        days = attachHotelAlternatives(days, hotelStaysOf(pool.candidates()));
        warnLegConflicts(days, legs, legWarnings);

        // ── 地理跨度：排完之后拿真实坐标回头校一遍（B11）──────────────────
        // 和首尾两天那两条是同一个理由：prompt 已经尽力了（每家酒店到候选景点的距离
        // 现在都算好写进了候选行，见 CandidateCodes.renderHotelLines），
        // 但"大概率照做"不是一个可以交付的承诺。
        // ⚠️ 它只写警告、**不改行程** —— 擅自动用户的点是另一种伤害。
        warnGeoConflicts(days, rules, warnings);

        // 车次的告警**放在最后**并入，不是随手 addAll 在上面。
        // warnings 的顺序是有约定的：最前面是"这次为什么这么查"（检索条件的规划说明），
        // 然后是检索层的降级，再是排程层的取舍，最后才是交通。
        // 车次虽然**执行**在最前，但它讲的是"行程里少/多了一行什么"，属于收尾的话 ——
        // 顺序按"因 → 果"排，不按"代码先跑到哪一行"排。
        warnings.addAll(legWarnings);

        // ── 溯源：从"检索层的账"投影出"给用户看的账" ────────────────────
        List<String> allQueries = new ArrayList<>(pool.provenance().queries());
        allQueries.addAll(legQueries);
        PlanProvenance provenance = new PlanProvenance(
                retrievalPlan,
                allQueries,
                pool.provenance().searchRounds(),
                pool.provenance().candidatesFound(),
                // 用检索层的 candidatesKept（景点+酒店），而不是只数景点。
                // 为什么改了：以前酒店不进行程，算进来是虚的；现在它真的落到每一天上了，
                // 那"有多少条候选可用"就该把酒店算进去 —— 否则用户会看到
                // "可用 10 条"而行程里明明还多了 1 家酒店，这数字就对不上他自己数出来的结果。
                pool.provenance().candidatesKept(),
                pool.provenance().rejected().stream()
                        .map(r -> new RejectedCandidate(r.name(), r.reason()))
                        .toList(),
                pool.provenance().relaxed(),
                System.currentTimeMillis() - start
        );

        log.info("行程生成完成：城市={} 天数={} 候选景点={} 候选酒店={} 上游调用={} 总耗时={}ms",
                params.city(), params.days(), pois.size(), hotels.size(),
                pool.provenance().upstreamCalls(), provenance.elapsedMs());

        return new TravelPlan(assembled.summary(), days, warnings, provenance, spotPool);
    }

    /**
     * 从混合候选池里挑出**景点**，做成界面用的备选池（酒店不进）。
     *
     * 抽成 static 纯函数（和 LegService 里那些一样）是为了它能被单测直接钉住，
     * 因为这里有一条真会写错的规则：**酒店不能混进来**。
     * 池子在 RetrievalService 里是混装的（`Candidate` 是判别联合），
     * 想让酒店溜进来一步都不用做错 —— 只要把 `instanceof PoiCandidate` 那个判断
     * 换成"两种都收下"就行，编译得过、跑得起来，
     * 只是界面上会多出一条「换一个 → 广州奥园喜来登酒店 ¥943」，而且全程不报错。
     * 这正是这个工程反复遇到的那类错：**不是逻辑难，是没人拦着顺手的写法。**
     *
     * 顺序原样保留（飞猪的相关性顺序）。前端会按"离当天其它点多近"再排一次 ——
     * 那是**展示**顺序，不是数据的顺序：一旦在这里排过，前端拿到的就是一份
     * 已经被加工过的输入，它的二次排序就变成了"在别人的排序结果上再排"，说不清谁说了算。
     */
    static List<SpotOption> spotPoolOf(List<Candidate> candidates) {
        List<SpotOption> out = new ArrayList<>();
        for (Candidate c : candidates) {
            if (c instanceof PoiCandidate p) {
                out.add(SpotOption.from(p));
            }
        }
        return out;
    }

    /* ============================================================
     * 城际交通 —— 唯一一条"每段不能太长"的约束落到数据上的地方
     * ============================================================ */

    /**
     * 查出发地 ↔ 目的地两段真实车次。
     *
     * ── 为什么是两段，而且是"家→目的地"和"目的地→家" ──────────────
     *   这个产品的身份约束是"带孩子，每一段移动都不能太长"。而"每一段"在单城市行程里
     *   就只有这两段 —— 去和回。**回程不是另一类东西，它和去程是同一张图上的同一种边。**
     *   所以这里对两段的处理完全对称（同一套查询、同一套判定），唯一的区别是挑班次的方向：
     *   去程怕太晚**到**（DAY 1 整天废掉），回程怕太晚**到家**（把疲劳带到第二天）。
     *
     *   ⚠️ 所以"回程要不要改成飞机 / 要不要分段"这个问题的答案不是设计出来的，
     *      是**算出来的**：如果目的地到家这一段 ≤ 上限，一段就回去了。
     *      如果它超了，正确动作也不是加航班，而是**换一个近的收尾城市** ——
     *      但那是多城市行程的事，不在这一层。
     *
     * ── 它为什么在 2026-10-13 从"排完之后"挪到了"排之前" ────────────
     *   原来这里是这么写的："车次的产物只是'每天怎么到的'那一行，它不影响模型挑哪些景点。
     *   放在之前的话，一旦车次查询慢，模型就要在它后面等 —— 把两次独立耗时串成了叠加。"
     *
     *   两句话都不对，而且是被真实行程撞出来的：
     *     ① **车次确实影响排程，影响的是最要紧的两天。** 它决定第 1 天最早能几点开始
     *        （去程 10:39 到站，"10:00 逛动物园"就是个做不到的安排），
     *        也决定最后一天最晚能玩到几点（返程 14:16 发车，"15:00 珠江夜游"就无从谈起）。
     *        实测那份行程两个都犯了，而每一处单看都挑不出毛病 ——
     *        **错的是两处各自算对了、却不知道对方。**
     *     ② "两次独立耗时叠加"这个担心不成立：车次和检索共用**同一把限流锁**
     *        （FlyaiCaller 里那唯一的 lock），它们本来就是串行的，先后不改变总时长。
     *        真正会把耗时叠起来的是模型调用，而模型调用本来就在两者之后。
     *
     *   还有一件事让这个改动几乎免费：车次结果会进缓存，演示当天是命中的，0 成本。
     *
     * ── 失败处理：三条路，只有一条会往用户眼前写话 ──────────────────
     *   FOUND          → 挂上真实车次，不吭声（正常情况）
     *   NO_EDGE        → **这是结论**：两地之间没有这么快的直达。写进 warnings，
     *                    并说清"要么接受更长的车程、要么换目的地"—— 用户必须知道，
     *                    因为他可能正准备去一个带娃会很累的地方。
     *   UPSTREAM_ERROR → **这不是结论**：只是没查成。也必须写，但话要完全不同 ——
     *                    绝不能让它听起来像"没有车次"。把查询故障说成"没有这种走法"，
     *                    会让用户**永远放弃一条其实存在的路线**，而且不报错、查不出来。
     *
     *   三条路都不会让 /generate 失败。判据还是那一句：**失败之后还有没有出路？**
     *   有 —— 行程本身是完整的，只是缺一行交通。那就把这一行留空 + 说一句实话，
     *   而不是把已经排好的整份行程扔掉。
     */
    private Legs searchLegs(TravelParams params, List<String> queries, List<String> warnings) {
        if (!canQueryLegs(params)) {
            return Legs.empty();
        }
        LocalDate start = LocalDate.parse(params.startDate());
        // ⚠️ 回程日按**用户说的天数**算，不按"装配出来几天"算。
        //    这两个数平时相等，但对不上时该听用户那个：玩几天是他定的决定，
        //    而"模型排出了几天"是我们这边的中间结果 —— 让中间结果决定返程日期是本末倒置。
        LocalDate end = start.plusDays(tripDays(params) - 1);

        Legs.Options outbound = searchLeg(params.origin(), params.city(), start,
                LegService.Direction.OUTBOUND, "出发地到目的地", queries, warnings);
        Legs.Options inbound = searchLeg(params.city(), params.origin(), end,
                LegService.Direction.RETURN, "目的地回出发地", queries, warnings);
        return new Legs(outbound, inbound);
    }

    /**
     * 只做"挂"这件事。查在 searchLegs 里 —— 依赖不同（那边只依赖画像，这边依赖排出来的天）
     *
     * ⚠️ 备选走法**只在它被查出来的那一段**上挂。判断依据是"from/to 和这一段一致"，
     *    不靠"第 1 天 / 最后一天"推断 —— 那两条规则只在单城市行程下成立
     *    （和 TravelDay 里为什么不放 `nights` 是同一条理由）。
     */
    private List<TravelDay> attachLegs(List<TravelDay> days, Legs legs) {
        if (days.isEmpty() || !legs.hasAny()) {
            // 没问到出发地是最常见的分支，不该为它付一次 list 拷贝
            return days;
        }
        List<TravelDay> withLegs = new ArrayList<>(days);
        if (legs.outbound().primary() != null) {
            TravelDay first = days.get(0);
            withLegs.set(0, new TravelDay(first.day(), first.theme(), first.spots(),
                    legs.outbound().primary(), legs.outbound().alternatives(), first.hotel(),
                    first.hotelAlternatives()));
        }
        if (legs.inbound().primary() != null) {
            int last = days.size() - 1;
            TravelDay day = days.get(last);
            withLegs.set(last, new TravelDay(day.day(), day.theme(), day.spots(),
                    legs.inbound().primary(), legs.inbound().alternatives(), day.hotel(),
                    day.hotelAlternatives()));
        }
        return withLegs;
    }

    /* ============================================================
     * 住宿备选 —— "今晚还能住哪儿"
     * ============================================================ */

    /**
     * 给每天挂上"同城还能住哪几家"。
     *
     * ── 为什么是后处理，而不是装配时一并填 ───────────────────────────
     *   装配拿到的酒店池是**截断过的**（MAX_HOTELS_IN_PROMPT）。而备选要的是**完整池子**，
     *   因为"给模型看多少"和"给用户多少可选"是两件事 —— 景点那边早就这么分了
     *   （见 spotPoolOf：模型看 30 条，用户看到全部 41 条）。酒店现在是同一个道理。
     *   把它做成和 attachLegs 一样的后处理，是为了**让"截断"和"投影"在代码上分得开**：
     *   只要它和装配写在同一个方法里，早晚有人会顺手用那个截断后的变量。
     *
     * ── 为什么每天都填，而不是只填连住段的第一天 ────────────────────
     *   连住时相邻几天的备选是同一份，确实是重复的。但判断"哪几天属于同一段连住"
     *   需要比较 hotelId 并回溯，而前端 mergeStays 已经在做这件事了（它本来就要合并显示）。
     *   契约层多加一个"能算出来的"字段，就多一个能对不上的地方 ——
     *   这条规矩在这个项目里已经写过好几次（见 TravelDay 的注释）。
     */
    static List<TravelDay> attachHotelAlternatives(List<TravelDay> days, List<HotelStay> hotelPool) {
        // ⚠️ hotelPool 的判空必须在 isEmpty 之前 —— 这是写完当天被测试抓出来的：
        //    写成 `hotelPool.isEmpty()` 时，传 null 会 NPE，而"没有酒店候选"是一个
        //    完全正常的输入（needHotel=false 的那次生成）。这个方法本来就是为了
        //    "没东西可挂时原样返回"，却在最该兜住的那个输入上崩了。
        if (days.isEmpty() || hotelPool == null || hotelPool.isEmpty()) {
            return days;
        }
        List<TravelDay> out = new ArrayList<>(days.size());
        for (TravelDay d : days) {
            String chosen = d.hotel() == null ? null : d.hotel().hotelId();
            out.add(new TravelDay(d.day(), d.theme(), d.spots(), d.transfer(), d.alternatives(),
                    d.hotel(), hotelAlternativesOf(hotelPool, chosen)));
        }
        return out;
    }

    /**
     * 从酒店池里挑出"除主选之外还能住的几家"。
     *
     * ── 取哪几家：**跨档轮转**，不是"最便宜的 3 家" ──────────────────
     *   这是真跑一次才发现的（2026-10-14，广州→珠海 3 天）：配置改成查两档之后，
     *   备选清单里全是"舒适型"（¥108 / ¥112 / ¥145）—— 因为珠海最便宜的几家恰好都在舒适档。
     *   第二档（四星/高档）**一家都没露出来**。
     *
     *   后果不只是"少看了几家"：那样一来第二档那次查询就是**纯浪费** ——
     *   多花一次上游调用、多占一份 30 秒预算，换来的东西用户永远看不到。
     *   而这个功能存在的理由恰恰是"用户觉得档次高，想看到别的选择"。
     *
     *   所以规则是：**按 star 分组，各组轮流出一家**（组内按价格升序），取满 3 家，
     *   再把这 3 家按价格升序展示。轮转负责"跨档"，最后的排序负责"读起来不乱"——
     *   两件事分开做，是因为它们回答的是不同的问题（选出谁 / 怎么摆）。
     *
     * ⚠️ 只有一个档位时（配置写成 `"3"`），轮转自然退化成"按价格取前 3" ——
     *    不需要为单档单独写一条分支。
     *
     * ── 价格未知（null）排最后 ─────────────────────────────────────
     *   `null` 的含义是"飞猪上没有可订房型"，不是"免费"。把它当成 ¥0 排到最前面，
     *   用户点进去会发现订不了 —— 那比不显示更糟。（HotelStay 注释里那句说的是同一件事。）
     *
     * 抽成 static 纯函数是为了能被单测直接钉住 —— 这里有三条真会写错的规则：
     * 排除主选、跨档轮转、null 排最后。
     */
    static List<HotelStay> hotelAlternativesOf(List<HotelStay> pool, String excludeHotelId) {
        if (pool == null || pool.isEmpty()) {
            return List.of();
        }
        Comparator<HotelStay> byPrice = Comparator.comparing(HotelStay::priceYuan,
                Comparator.nullsLast(Comparator.naturalOrder()));

        // ① 先排除主选，再取。反过来（先取前 N 再排除）会让主选占掉一个名额 ——
        //    池子是 [主选, 便宜, 中, 贵] 时就只给得出 2 家，而且不报错。
        List<HotelStay> candidates = new ArrayList<>();
        for (HotelStay h : pool) {
            if (excludeHotelId == null || !excludeHotelId.equals(h.hotelId())) {
                candidates.add(h);
            }
        }
        candidates.sort(byPrice);

        // ② 按档位分组（组内已经是价格升序，因为上面刚整体排过，分组保持插入顺序）
        Map<String, List<HotelStay>> byTier = new LinkedHashMap<>();
        for (HotelStay h : candidates) {
            byTier.computeIfAbsent(tierOf(h), k -> new ArrayList<>()).add(h);
        }

        // ③ 轮转取。一轮从每个档位各拿一家，拿完一圈再拿第二圈 ——
        //    这样"档位少但每家都便宜"和"档位多"两种情况都不会让某一档彻底消失。
        List<HotelStay> picked = new ArrayList<>();
        for (int round = 0; picked.size() < MAX_HOTEL_ALTERNATIVES; round++) {
            boolean anyThisRound = false;
            for (List<HotelStay> tier : byTier.values()) {
                if (round < tier.size()) {
                    picked.add(tier.get(round));
                    anyThisRound = true;
                    if (picked.size() >= MAX_HOTEL_ALTERNATIVES) {
                        break;
                    }
                }
            }
            if (!anyThisRound) {
                break;
            }
        }

        // ④ 展示顺序：价格升序。用户看这一行是为了找"更便宜的"，顺序乱了就白瞎了。
        picked.sort(byPrice);
        return List.copyOf(picked);
    }

    /**
     * 档位标识 —— 直接用 star 那个中文词（"舒适型" / "四星级" / "高档型" …）。
     *
     * 为什么不映射成数字：实测上游的档次词不一定和星级对应
     * （同一档里"四星级"和"高档型"混着出现，而 `--hotel-stars 4` 回来的就是这两种）。
     * 硬做一张映射表，一旦上游多出一个新词（比如"轻奢型"）就会静默归到"未知"那一组，
     * 而"未知"那一组会和其他档一起轮转 —— 结果是档次跨度看起来对了、其实错了。
     * 用原词做 key：新词自己成一组，最多是"多一组"，不会把别的档挤掉。
     */
    private static String tierOf(HotelStay h) {
        return h.star() == null ? "" : h.star();
    }

    /**
     * 从混合候选池里挑出**酒店**，投影成界面用的备选。
     *
     * 和 spotPoolOf 是一对：那边挑景点、这边挑酒店，两个判断都写成 `instanceof` 的正向筛选。
     * 顺序**不在这里排**（原样保留飞猪的相关性顺序）—— 排序是给用户看的那一步的事
     * （hotelAlternativesOf 里的价格升序），两者分开，将来改展示策略不会动到数据。
     */
    static List<HotelStay> hotelStaysOf(List<Candidate> candidates) {
        List<HotelStay> out = new ArrayList<>();
        if (candidates == null) {
            return out;
        }
        for (Candidate c : candidates) {
            if (c instanceof HotelCandidate h) {
                // tips 传 null：那是模型"为什么住这儿"的表达，备选没有经过模型判断。
                out.add(PlanAssembler.toStay(h, null));
            }
        }
        return out;
    }

    /** orDefaults 已经保证 >= 1，这里只是把"万一"兜住 —— 它要去参与日期减法 */
    private static int tripDays(TravelParams params) {
        return params.days() == null || params.days() <= 0 ? 1 : params.days();
    }

    /* ============================================================
     * 排完之后再回头校一遍首尾两天 —— 模型没听话时的兜底
     * ============================================================ */

    /** 到站之后要出站、进城才谈得上开始玩。这个数字和 prompt 第 13 条里那句"至少 1 小时"是同一个 */
    private static final int ARRIVE_TO_FIRST_SPOT_MINUTES = 60;

    /** 最后一个点要留出去车站 + 取票进站的时间。和 prompt 第 14 条里那句"早至少 3 小时"是同一个 */
    private static final int SPOT_TO_STATION_MINUTES = 180;

    /**
     * 拿真实车次回头校第一天的开头和最后一天的结尾，对不上就说出来。
     *
     * ── 为什么 prompt 里已经写清楚了，这里还要再查一遍 ────────────────
     *   因为这个工程对 prompt 的定位一直是：**它是优化，不是保证。**
     *   我们把时刻喂给模型（见 PlanComposer.renderLegs），它会**大概率**照做；
     *   但"大概率"不是一个可以交付的承诺 —— 模型偶尔就是会无视约束，
     *   而这次无视的后果很具体：用户拿到一份**自己都看得出矛盾**的行程
     *   （下午 15:00 还排着夜游，返程车 14:16 就走了）。
     *
     *   所以这里做的不是"再劝它一次"，而是**在交付之前把矛盾找出来说清楚**。
     *   注意它**只写警告、不改行程**：擅自动用户的景点（删掉或改时间）是另一种伤害 ——
     *   用户会觉得"我明明看到了那个点，怎么生成出来就没了"。
     *   说出问题、把决定权还给他，和这个工程处理"车次查不到"时是同一种做法。
     *
     * ⚠️ 时刻读不出来的一律**跳过**而不是报错：那可能是模型写了个"下午三点"，
     *    而这种情况已经有 PlanAssembler 在管，不该在这里变成第二条重复的告警。
     */
    private void warnLegConflicts(List<TravelDay> days, Legs legs, List<String> warnings) {
        if (days.isEmpty()) {
            return;
        }
        // ⚠️ 只拿 **primary** 校（见 Legs.Options）：这一天的安排就是按它排的，
        //    拿备选走法去校会报出一堆"你其实没选的那班车"的假警告。
        String start = firstDayConflict(days.get(0), legs.outboundTransfer());
        if (start != null) {
            warnings.add(start);
        }
        String end = lastDayConflict(days.get(days.size() - 1), legs.inboundTransfer());
        if (end != null) {
            warnings.add(end);
        }
    }

    /* ============================================================
     * 排完之后再校一遍"这一天会不会全耗在路上"
     * ============================================================ */

    /**
     * 拿真实坐标回头校每一天的地理跨度。
     *
     * ── 这一层要防的错，形状和首尾两天那两条一模一样 ─────────────────
     *   **每一天单独看都成立，矛盾只存在于两天之间。**
     *
     *   实测（2026-10-13，日志里那份真实生成）：模型让用户第 1 天晚上住桂林市区
     *   （象鼻山旁的地球村雅致漓江酒店），第 2 天早上 09:30 去阳朔的漓江竹筏码头 ——
     *   两地**直线 42.4 公里**。可是拆开看：第 1 天三个点全在市区（最远 1.8 公里）✓，
     *   第 2 天两个点也都在阳朔（4.4 公里）✓。**每天都没毛病，两天之间是一道沟。**
     *   而当时没有任何检查会跨天看，所以它一路绿灯通过。
     *
     *   顺便分清它和另一类错的区别：这**不是**"模型编了东西"（它没编，每个点都真实存在），
     *   而是"它缺一个前提"（它不知道这两处隔多远）。前者的解法是白名单；
     *   后者的解法是把前提给它（已做，见 CandidateCodes.renderHotelLines）
     *   加一道本地兜底（就是这里）。**两类错用两种药，混着用只会去加一堆没用的约束条款。**
     *
     * ⚠️ 阈值用的是 `rules.maxDistanceKm()` —— PlanRules 里那个「相邻两个点最大距离」。
     *    它的注释一直写着"属于**排期**阶段的约束（要拿到顺序才算得了），校验层不用它"，
     *    可排期阶段（那个大模型）**根本算不了经纬度距离** ——
     *    于是这个约束从写下来那天起就没人执行过。现在由这一层接住。
     *    **要么用、要么删，不能让一个看着有约束、实际是空的字段挂在配置里** ——
     *    那比没有更危险，因为它会让人以为这件事已经有人管了。
     *
     * ⚠️ 只写警告、不改行程。理由和 firstDayConflict 那边一样：
     *    擅自动用户的景点（删点、换酒店）会让他觉得"我明明看到的那个怎么没了"。
     */
    private void warnGeoConflicts(List<TravelDay> days, PlanRules rules, List<String> warnings) {
        int maxKm = rules.maxDistanceKm();
        if (days.isEmpty() || maxKm <= 0) {
            return;
        }
        for (int i = 0; i < days.size(); i++) {
            String spread = spotSpreadConflict(days.get(i), maxKm);
            if (spread != null) {
                warnings.add(spread);
            }
            if (i > 0) {
                // 早上从**昨晚的住处**赶到今天的第一个点 —— 实测那 42.4 公里就卡在这一段。
                // 第 1 天没有"昨晚"：那天早上是从家里或车站出发的，
                // 前一晚住哪根本不在这份行程里，硬套一家酒店只会报出一条假警告。
                String commute = morningCommuteConflict(days.get(i), days.get(i - 1), maxKm);
                if (commute != null) {
                    warnings.add(commute);
                }
            }
        }
    }

    /**
     * 当天相邻两个点隔得太远。没冲突返回 null。
     *
     * ⚠️ 顺序按 **time 排**，不按列表顺序 —— 和 firstDayConflict 里那条是同一个讲究：
     *    "列表第一个"是位置，"最早的点"是时间语义，而模型实测就是会把 14:00 写在 09:30 前面。
     *    在那边的后果是漏掉一个点；在这边更隐蔽：顺序一乱，算出来的"相邻两点"
     *    其实是两个根本不挨着的点，**报出来的那个数字就是错的** ——
     *    而一个错的数字比一句"我算不出来"更糟。
     *
     * 报的是**超标里最严重的那一段**，不是第一段超标的：
     *    同一天若有两段都超了，用户最该知道的是最夸张的那段；
     *    一次报三段只会让这一天把别的天淹没掉。
     */
    static String spotSpreadConflict(TravelDay day, int maxKm) {
        if (day == null) {
            return null;
        }
        List<TravelSpot> ordered = sortedByTime(day);
        String worst = null;
        double worstKm = maxKm;
        for (int i = 1; i < ordered.size(); i++) {
            TravelSpot from = ordered.get(i - 1);
            TravelSpot to = ordered.get(i);
            Double km = Geo.between(from.lat(), from.lng(), to.lat(), to.lng());
            if (km != null && km > worstKm) {
                worstKm = km;
                worst = "第 " + day.day() + " 天的「" + from.name() + "」和「" + to.name() + "」之间隔了 "
                        + Geo.kmText(km) + " —— 带娃光这一段路就要花掉不少时间，"
                        + "换掉其中一个、或者把它挪到另一天，会从容很多";
            }
        }
        return worst;
    }

    /**
     * 今天第一个点离**昨晚的住处**太远。没冲突返回 null。
     *
     * 这是整组检查里最要紧的一条 —— 实测那 42.4 公里正好卡在这里。
     * "今晚住哪"和"明早几点出发"看着是两天的事，对用户却是**同一次出门**：
     * 他要带着孩子从住处赶到第一个点，而景点的开门时间是定死的。
     * 行程里"第 1 天晚上"和"第 2 天早上"是两行，现实里是同一个连续的动作。
     */
    static String morningCommuteConflict(TravelDay today, TravelDay yesterday, int maxKm) {
        if (today == null || yesterday == null || yesterday.hotel() == null) {
            return null;
        }
        HotelStay stay = yesterday.hotel();
        if (!Geo.has(stay.lat(), stay.lng())) {
            return null;
        }
        TravelSpot first = earliestSpot(today);
        if (first == null || !Geo.has(first.lat(), first.lng())) {
            return null;
        }
        Double km = Geo.between(stay.lat(), stay.lng(), first.lat(), first.lng());
        if (km == null || km <= maxKm) {
            return null;
        }
        return "第 " + yesterday.day() + " 天晚上住「" + stay.name() + "」，"
                + "第 " + today.day() + " 天第一个点「" + first.name() + "」却在 "
                + Geo.kmText(km) + "以外 —— 第二天一早要跨这一趟路，"
                + "把住处换到那一片、或者干脆把那一天整片挪过去，都会轻松很多";
    }

    /**
     * 按时刻排好的这一天。**读不出时刻的点排到最后，而不是丢掉。**
     *
     * 丢掉的话，它前后的两个点会"隔着它"变成相邻 —— 而它们现实中并不相邻，
     * 那等于凭空造出一段不存在的路。
     */
    private static List<TravelSpot> sortedByTime(TravelDay day) {
        List<TravelSpot> ordered = new ArrayList<>(day.spots());
        Comparator<LocalTime> byClock = Comparator.nullsLast(Comparator.naturalOrder());
        ordered.sort(Comparator.comparing(s -> timeOfSpot(s.time()), byClock));
        return ordered;
    }

    /** 这一天**时刻最早**的那个点；一个都读不出时刻就回 null（回 null = 这项不校验，不是报错） */
    private static TravelSpot earliestSpot(TravelDay day) {
        TravelSpot earliest = null;
        LocalTime earliestTime = null;
        for (TravelSpot spot : day.spots()) {
            LocalTime t = timeOfSpot(spot.time());
            if (t != null && (earliestTime == null || t.isBefore(earliestTime))) {
                earliestTime = t;
                earliest = spot;
            }
        }
        return earliest;
    }

    /**
     * 第 1 天是不是排在了到达之前。没冲突返回 null。
     *
     * ⚠️ 取的是**时刻最早**的那个点，不是 `spots().get(0)`。
     *    "列表第一个"是位置，"最早的点"是时间语义 —— 两者一不一致，取决于别人有没有排过序，
     *    而那份保证不在这个方法手上。实测模型偶尔就是会把 14:00 写在 09:30 前面。
     *    依赖一个我们没被承诺过的前提，正是这个工程反复踩的那类坑：**规则对，取值方式错。**
     *
     * 抽成 static 纯函数（和 LegService 里那些一样），是为了它能被单测直接钉住 ——
     * 依赖越少，测起来越接近"验证规则本身"而不是"验证一堆假服务的接线"。
     */
    static String firstDayConflict(TravelDay day, Transfer outbound) {
        if (outbound == null || day == null || day.spots().isEmpty()) {
            return null;
        }
        LocalTime arrive = timeOf(outbound.arrDateTime());
        if (arrive == null) {
            return null;
        }
        // "最早的点"这段逻辑和上面的地理检查共用（见 earliestSpot）——
        // 两处都靠它取参，所以它一旦写错，两个检查会**一起**错到同一个方向去。
        TravelSpot earliest = earliestSpot(day);
        if (earliest == null) {
            return null;
        }
        LocalTime earliestTime = timeOfSpot(earliest.time());
        LocalTime earliestAllowed = arrive.plusMinutes(ARRIVE_TO_FIRST_SPOT_MINUTES);
        if (!earliestTime.isBefore(earliestAllowed)) {
            return null;
        }
        return "第 1 天 " + earliestTime + " 就安排了「" + earliest.name() + "」，"
                + "但去程车 " + clock(outbound.arrDateTime()) + " 才到" + outbound.arrStation()
                + " —— 出站进城还要时间，这一个大概率赶不上，往后挪一挪更稳妥";
    }

    /** 最后一天是不是排在了发车之后。没冲突返回 null。同样取**时刻最晚**的那个点（理由同上） */
    static String lastDayConflict(TravelDay day, Transfer inbound) {
        if (inbound == null || day == null || day.spots().isEmpty()) {
            return null;
        }
        LocalTime depart = timeOf(inbound.depDateTime());
        if (depart == null) {
            return null;
        }
        TravelSpot latestSpot = null;
        LocalTime latestTime = null;
        for (TravelSpot spot : day.spots()) {
            LocalTime t = timeOfSpot(spot.time());
            if (t != null && (latestTime == null || t.isAfter(latestTime))) {
                latestTime = t;
                latestSpot = spot;
            }
        }
        if (latestTime == null) {
            return null;
        }
        LocalTime latestAllowed = depart.minusMinutes(SPOT_TO_STATION_MINUTES);
        if (!latestTime.isAfter(latestAllowed)) {
            return null;
        }
        return "最后一天 " + latestTime + " 还安排了「" + latestSpot.name() + "」，"
                + "但返程车 " + clock(inbound.depDateTime()) + " 就从" + inbound.depStation() + " 发车"
                + " —— 这一天得在 " + latestAllowed + " 前收尾，否则赶不上车，去掉一个点会从容很多";
    }

    /**
     * "2026-11-21T14:16" → 14:16。
     *
     * 用**定长截取**而不是解析成 LocalDateTime：这个字符串的形状由 LegService.isoMinutes 保证
     * （契约里就是 ISO 到分钟），截取不会因为时区、夏令时之类的东西被别的东西影响。
     * 形状不对就回 null —— 校验宁可漏一条，也不要拿半个时刻去比大小，那会报出一句假警告。
     */
    private static LocalTime timeOf(String isoMinutes) {
        if (isoMinutes == null || isoMinutes.length() < 16) {
            return null;
        }
        try {
            return LocalTime.parse(isoMinutes.substring(11, 16));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * 模型写的 "HH:mm" → LocalTime。
     *
     * 用宽松的 "H:mm" 而不是 LocalTime.parse 的默认格式：模型偶尔会写 "9:30"（不带前导零），
     * 用默认格式会解析失败 —— 而这里的失败是**静默的**（我们只是不校验了），
     * 那等于对最早出发的那一天放手不管。能容的就容掉。
     */
    private static LocalTime timeOfSpot(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(raw.strip(), HHMM_LOOSE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static final DateTimeFormatter HHMM_LOOSE = DateTimeFormatter.ofPattern("H:mm");

    /** "2026-11-21T14:16" → "14:16"（给用户看的文案里不该出现那一串 ISO） */
    private static String clock(String isoMinutes) {
        LocalTime t = timeOf(isoMinutes);
        return t == null ? isoMinutes : t.toString();
    }

    /**
     * 查一段，并把三种结局翻译成人话。
     *
     * @return 只有 FOUND 时非 null —— 调用方据此决定"这一天有没有交通信息"
     */
    private Legs.Options searchLeg(String from, String to, LocalDate date, LegService.Direction direction,
                                   String role, List<String> queries, List<String> warnings) {
        LegService.Leg train;
        try {
            train = legService.search(from, to, date.toString(), direction);
        } catch (IllegalArgumentException | FlyaiException e) {
            // 走到这里说明是**我们自己**的判定拒绝了这次查询（城市名不合法之类），
            // 而不是上游的问题。它也绝不能让整份行程失败 —— 画像里的城市名在 /generate
            // 这条路径上没有经过对话那层的 sanitize（脚本会直接调这个接口）。
            //
            // ⚠️ 这里必须抓 **IllegalArgumentException**，而不是只有 FlyaiException。
            //    `LegService.search` 内部已经把每一个 FlyaiException 都翻译成了 Outcome
            //    （见它的 catch 分支），所以 FlyaiException **根本逃不出这个方法**；
            //    能逃出来的只有 requireCity / 同城检查抛的 IllegalArgumentException。
            //    之前只抓后者不抓前者，等于守着一扇永远没人走的门、敞着一扇人人走的门 ——
            //    结果就是画像里写了个「广东周边」当出发地时，整个 /generate 报 500。
            //    （这是这个工程里第 N 次同一个形状的坑：**错的不是判断，是抓的那一层。**）
            warnings.add(role + "的车次没能查（" + e.getMessage() + "），行程里没有交通信息");
            return Legs.Options.empty();
        }
        queries.add(train.query());

        switch (train.outcome()) {
            // ⭐ **超限也要挂上。** "太长"是值得说的一句话，但绝不是"把车藏起来"的理由 ——
            //    藏起来用户连"要坐多久"都看不到，就更谈不上自己做决定了。
            case FOUND -> {
                if (train.overLimit()) {
                    warnOverLimit(role, train.transfer(), warnings);
                }
            }
            // 这是上游给出的**结论**，不是我们的推断 —— 所以可以直接写死这句话。
            case NO_EDGE -> warnNoEdge(role, from, to, train.detail(), warnings);
            // ⚠️ 千万不能说成"没有车次"。话里必须带"没查成"三个字的意思。
            case UPSTREAM_ERROR -> warnings.add(role + "的车次这次没查成（" + train.detail()
                    + "），行程本身没问题，只是交通那一行缺着 —— 稍后重新生成一次就会补上");
        }

        // ── 火车这条腿走不顺时，顺手问一次飞机（2026-10-14）──────────────────
        // 触发条件只有"我们已经知道火车这条路不行"这一种（见 wantsFlight 的注释）：
        // 每一次上游调用都是额度，而"要不要看飞机"这件事，先由那条 180 分钟的红线
        // 替我们筛掉了绝大多数情况（短途本来就不需要飞机这个备选）。
        // ⚠️ 注意它**只查一次**：去程一节、回程一节，没有"再查一个中转航班"这种事。
        LegService.Leg flight = wantsFlight(train)
                ? searchFlight(from, to, date, direction, role, queries)
                : null;

        Legs.Options options = optionsOf(train, flight);
        warnFlightAlternative(role, train, options, warnings);
        return options;
    }

    /**
     * 这一次要不要**额外**花一次调用去问飞机。
     *
     * 只有两种情况：
     *   ① 火车有，但最快的一班也超了上限（overLimit）—— 正是用户会想"那我坐飞机呢"的那一刻；
     *   ② 火车**根本没有**走法（NO_EDGE）—— 这一段路本来就指望不上火车。
     *
     * ⚠️ `UPSTREAM_ERROR` **故意不在里面**。这不是抠门，是判据必须站得住：
     *    查询失败说明**我们不知道火车行不行**，而"不知道"既不能推出"这段路很长"，
     *    也不能推出"该给个备选"。这时候多查一次飞机，要么是白花钱（路其实很短），
     *    要么更糟 —— 给出一份"只有飞机"的交通行，而真实的火车可能又快又便宜。
     *    那时用户看到的是"这段路只有飞机"，而正确的话是"车次这次没查成，重生成一次就会补上"。
     *    （把"不知道"说成一个结论，是这个工程反复踩的那类错。）
     *
     * 抽成 static 纯函数是为了能被单测钉住：**这条规则决定花不花钱**，
     * 而它的两个判据（overLimit / NO_EDGE）都不是随手写的。
     */
    static boolean wantsFlight(LegService.Leg train) {
        if (train == null) {
            return false;
        }
        return train.outcome() == LegService.Outcome.NO_EDGE
                || (train.outcome() == LegService.Outcome.FOUND && train.overLimit());
    }

    /**
     * 把"火车 + 飞机"两个各自独立的查询结果，合成一段路的**一组走法**。
     *
     * ── 谁是主选：**火车优先，但火车走不通时飞机顶上来** ──────────────
     *   火车有（哪怕超限）→ 主选是火车。理由：超限只是"超出了我们替他设的默认上限"，
     *     不是"不能坐"，而且火车票便宜、准点、站点在市区 —— 用户大概率还是要坐它。
     *     飞机这时是备选（进 alternatives，界面上并排显示，谁也不替谁做决定）。
     *   火车没有 / 没查成 → **飞机当主选**。这不是"飞机更好"，是因为
     *     排程需要"这一天几点到"这个事实，而飞机是我们手里唯一的真实时刻。
     *     没有它，第 1 天就只能按"一般情况 09:00 开始"排 —— 那正是 B7 要修的那个瞎排问题。
     *
     * ⚠️ 两个都没查到 → primary 为 null，且 alternatives 为空。
     *    调用方据此知道"这一段什么都没有"（和"有一段飞机"必须分得开）。
     *
     * 纯函数（不碰网络、不碰配置）—— 这是本轮唯一一条"产品判断"，
     * 所以它必须能被单测直接钉住，而不是埋在编排流程里。
     */
    static Legs.Options optionsOf(LegService.Leg train, LegService.Leg flight) {
        Transfer trainTransfer = transferOf(train);
        Transfer flightTransfer = transferOf(flight);
        if (trainTransfer != null) {
            return new Legs.Options(trainTransfer,
                    flightTransfer == null ? List.of() : List.of(flightTransfer));
        }
        return new Legs.Options(flightTransfer, List.of());
    }

    /** 只有 FOUND 才有一个能挂上去的 Transfer；其余三种结局（含没查过）都是 null */
    private static Transfer transferOf(LegService.Leg leg) {
        return leg != null && leg.outcome() == LegService.Outcome.FOUND ? leg.transfer() : null;
    }

    /**
     * 飞机这一次查询**不写警告**，只记账。
     *
     * 和火车那三条路（FOUND / NO_EDGE / UPSTREAM_ERROR 全都要说话）的区别在于：
     * 火车是**用户一定会遇到**的那一行，所以它每一种状态都得说清；而飞机是"我们多看一眼"，
     * 看不着并不改变任何已给出的结论（火车那句话已经说完了），
     * 多说一句"顺带查的航班没查到"只会让 warnings 变长而没有可行动的信息。
     * 真正的故障仍然进得了日志（见下），排障不受影响。
     *
     * ⚠️ 唯一必须说的情况是"**飞机成了主选**"—— 那已经不是备选了，
     *    这一天的时间安排是按它排的，用户必须知道。（所以这里有 warnFlightAlternative 那个判断。）
     */
    private LegService.Leg searchFlight(String from, String to, LocalDate date,
                                        LegService.Direction direction, String role,
                                        List<String> queries) {
        try {
            LegService.Leg leg = legService.searchFlight(from, to, date.toString(), direction);
            // 失败也记在 queries 里 —— 溯源面板要的是"真实发出过哪些命令"，
            // 而一次失败的调用同样花了额度（上游是按请求计的）。
            queries.add(leg.query());
            return leg;
        } catch (IllegalArgumentException | FlyaiException e) {
            log.info("{} 的航班这一次没查成（{}）—— 不影响已经给出的火车信息", role, e.getMessage());
            return null;
        }
    }

    /**
     * 查到飞机时，把这件事说出来 —— 而且**说清它是什么、不是什么**。
     *
     * 三句话缺一不可：
     *   ① 它是**查询当时**的价格（实测同一条航线 11-17 与 12-10 差 2.5 倍，浮动比火车大得多）；
     *   ② `durationMinutes` 是**纯飞行时间**，机场还要提前到 —— 不说这句，用户会把
     *      "2 小时"当成门到门时间，于是拿它和火车的 6 小时直接比（那是两种口径）；
     *   ③ **我们不替他判断划不划算**。价格浮动大到这个程度，任何"飞机更值"的自动结论
     *      都是在拿一个会飘的数字替他做决定。
     *
     * 只在真的查到飞机时才说 —— 查不到、查不成都不说（见 searchFlight 的注释）。
     */
    private static void warnFlightAlternative(String role, LegService.Leg train,
                                              Legs.Options options, List<String> warnings) {
        Transfer flight = null;
        for (Transfer alt : options.alternatives()) {
            if (alt.mode() == TransportMode.FLIGHT) {
                flight = alt;
            }
        }
        boolean isPrimary = flight == null && options.primary() != null
                && options.primary().mode() == TransportMode.FLIGHT;
        if (isPrimary) {
            flight = options.primary();
        }
        if (flight == null) {
            return;
        }
        boolean noTrain = train != null && train.outcome() == LegService.Outcome.NO_EDGE;
        warnings.add(role + (noTrain ? "这段路没有直达火车，但可以飞：" : "这段路也可以飞：")
                + describeLeg(flight)
                + "，纯飞行 " + humanMinutes(flight.durationMinutes())
                + "（机场还要提前 1.5~2 小时到，进门出门的时间比这个数字长得多），"
                + flightPrice(flight)
                + (isPrimary
                    // 飞机成了主选时，"要不要坐"就不是个可选项了 —— 我们要让用户知道
                    // 行程当天的时间是**按这班航班**排的。
                    ? "—— 行程第 1 天的开始和最后一天的收尾都是按这班航班排的"
                    : "—— 要不要改坐飞机由你定，我们不做划不划算的判断"));
    }

    /** "票价 ¥610"；取不到价时说"票价未知"，**绝不写 ¥0** */
    private static String flightPrice(Transfer flight) {
        return flight.priceYuan() == null
                ? "票价未知（当时没查到）"
                : "票价 ¥" + trimYuan(flight.priceYuan()) + "（查询当时的参考价，机票浮动比火车大）";
    }

    /** 610.0 → "610"；610.5 → "610.5"。价格是给人看的，不要拖一个 ".0" */
    private static String trimYuan(double yuan) {
        return yuan == Math.floor(yuan) && !Double.isInfinite(yuan)
                ? String.valueOf((long) yuan)
                : String.valueOf(yuan);
    }

    /**
     * 有车、但最快的一班也超过了单段上限 —— 把车摆出来，同时**如实说它超了**。
     *
     * ── 为什么它必须是单独一条，不能复用 warnNoEdge ──────────────────
     *   warnNoEdge 说的是"这段路没有走法"，用户该做的动作是**换个目的地**；
     *   这里说的是"有走法，但它要 X 小时"，用户该做的动作是**自己决定接不接受**。
     *   两句话的下一步完全不同，混成一句就等于替用户把选择收走了。
     *
     *   （实测症状就是这么来的：深圳 → 上海本来只差一句"高铁 6.5 小时"，
     *     用户看到的却是"在 180 分钟内没有直达车次" —— 一句听起来很确定的假话。）
     *
     * ── 为什么仍然把车留在行程里 ────────────────────────────────────
     *   上限是我们替他设的**默认**，不是他的原话。他可能就是要坐这趟高铁。
     *   这和 LegService.pick 里"一条都不满足时段就退回第一条"是同一条原则：
     *   **给一份真实但不理想的东西，永远好过把选择拿走。**
     *
     * 文案里有三件事缺一不可：哪一段（role）、最快的一班要多久（具体数字）、
     * 以及这条线在哪儿调。少任何一个，用户就只能干瞪眼。
     */
    private void warnOverLimit(String role, Transfer t, List<String> warnings) {
        warnings.add(role + "这段路带娃偏累：火车最快的" + t.from() + " 到 " + t.to()
                + " 一班也要 " + humanMinutes(t.durationMinutes())
                + "（" + describeLeg(t) + "）—— 超过单段 " + legService.limitMinutes()
                + " 分钟这条上限。这趟车仍然安排在行程里，你可以照它订票；"
                + "觉得这条线该放宽或收紧，改 application.yaml 的 max-leg-minutes");
    }

    /** "高铁G99 08:00 广州南 → 14:40 上海虹桥" / "深航ZH9501 08:20 宝安 → 10:25 虹桥"
     *  —— 说得出是哪一班，用户才能自己去核对 */
    private static String describeLeg(Transfer t) {
        String no = t.serviceNo() == null ? (t.mode() == null ? "班次" : t.mode().label()) : t.serviceNo();
        return no + " " + clock(t.depDateTime()) + " " + t.depStation()
                + " → " + clock(t.arrDateTime()) + " " + t.arrStation();
    }

    /**
     * 400 → "6 小时 40 分"。带娃出行里，"6 小时 40 分"和"400 分钟"不是一个感受。
     *
     * 耗时读不出来时**不编一个数字** —— 给一句不带数字的话，而不是拿一个平均值顶上。
     */
    static String humanMinutes(Integer minutes) {
        if (minutes == null || minutes <= 0) {
            return "一段不短的车程";
        }
        int h = minutes / 60;
        int m = minutes % 60;
        if (h == 0) {
            return m + " 分钟";
        }
        return m == 0 ? h + " 小时" : h + " 小时 " + m + " 分";
    }

    /**
     * 两座城市之间**真的没有走法** —— 把结论说成**可行动**的一句话。
     *
     * ⚠️ 走到这里的前提已经变了：粗筛窗口放宽到 12 小时之后，这句话的含义是
     *    "连 12 小时内的走法都没有"，而**不是**"超出我们那条上限了"。
     *    后者有专门的 {@link #warnOverLimit}，而且那时车是**有**的。
     *    两者的分界线就是"车到底存不存在"——
     *    把"有车但太长"说成"没有走法"，用户会**永远放弃一条其实能走的路线**。
     *
     * 为什么不能只说"没有查到车次"：用户看到这句话会以为系统坏了，然后重试，
     * 而重试永远不会成功（是真的没有）。他需要知道的是**该怎么办**。
     * 而且得说清这是"带孩子"的约束，否则他会觉得系统管得太宽。
     */
    private void warnNoEdge(String role, String from, String to, String detail, List<String> warnings) {
        warnings.add(role + "这段路带娃偏累：" + detail
                + "。飞猪上没有更快的走法 —— 要么换一个近一些的城市，"
                + "要么这段路你自己安排（比如飞机）");
    }

    /**
     * 画像里凑不凑得出"一段城际交通"。
     *
     * 三个条件缺一不可，而且**每一种缺失都是安静的**：
     *   origin 没问到 → 不知道从哪走。不查、也**不报错**，因为"缺信息"不等于"出错"。
     *   startDate 没有 → 车次价格和班次都不对应当天，查了反而是误导。这一条要说一声，
     *                   因为它和 origin 不同：出发日期是用户**很可能愿意补**的一个信息，
     *                   而"从哪出发"他自己肯定知道，只是还没说。
     *   origin == city → 本地出行。这是合法的（用户就说"我们就在珠海想玩 6 天"），
     *                   所以更不能在这里报"没有查到车次"。
     */
    private boolean canQueryLegs(TravelParams params) {
        if (blank(params.origin()) || blank(params.city()) || params.sameCity()) {
            return false;
        }
        if (blank(params.startDate())) {
            return false;
        }
        return true;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * 把画像里的出发日期收拾干净：**过去的顺延到最近一次，解析不出来的丢掉**。
     *
     * ── 为什么这里有第二道（第一道在 ChatComposer.sanitize）────────────────
     *   因为这份画像不一定来自"模型刚说完"：
     *     ① 验证脚本 / 排障会直接调 `/generate`，只给 city 和日期；
     *     ② 前端会把上一次拿到的画像原样传回来，那是**过去某一刻**的画像 ——
     *        今天下午存的"11月17日"是未来，一个月后再点生成就是过去了。
     *   而一个过去的日期在飞猪那边会直接变成
     *   `{ status: 1, message: "…入离时间校验失败" }` —— 一个我们自己能免费修掉的问题，
     *   没有理由让它变成一次失败的请求。**本地能重算的，就不花上游调用、也不报错。**
     *   （和 RuleChecker 的"放宽优先于补查"是同一条优先级。）
     *
     * ⚠️ 修了就要说（写进 warnings）。用户看到的日期和他嘴里说的不一样，
     *   必须有一个地方解释得清 —— 否则他会以为系统把日期弄丢了。
     */
    private static TravelParams normalizeTripDate(TravelParams params, List<String> warnings) {
        String raw = params.startDate();
        if (raw == null || raw.isBlank()) {
            return params;
        }
        TravelDates.Normalized date = TravelDates.normalize(raw);
        if (date.date() == null) {
            // 解析不出来。必须在这里清掉，而不是原样传下去 ——
            // 下游 hotelArgs 会拿它做 `LocalDate.parse(...).plusDays(days)`，
            // 一个非 ISO 的日期在那里会**直接抛 DateTimeParseException**，
            // 最后变成一句"服务开小差了"，而真正的原因只是日期格式不对。
            warnings.add("出发日期「" + raw + "」不是一个有效的日期，已忽略 —— 可以重新说一次出发日");
            return params.withStartDate(null);
        }
        if (date.corrected()) {
            warnings.add("出发日期 " + raw + " 已经过去了，已按 " + date.date() + " 安排");
            return params.withStartDate(date.date());
        }
        return params;
    }
}
