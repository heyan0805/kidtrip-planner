package com.hellen.kidtrip.retrieval;

import com.hellen.kidtrip.candidate.CandidateNormalizer;
import com.hellen.kidtrip.flyai.CityNames;
import com.hellen.kidtrip.flyai.FlyaiException;
import com.hellen.kidtrip.flyai.FlyaiProperties;
import com.hellen.kidtrip.flyai.raw.FlyaiTrain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 查一段真实的城际交通：从哪到哪、坐哪趟、几点发几点到、多久、多少钱。
 *
 * ── 它存在的理由（用户故事）────────────────────────────────────
 *   这个项目唯一的身份约束是"**带孩子出行，每一段移动都不能太长**"。
 *   在这之前，行程里"怎么去"这一行根本没有，或者由模型编 ——
 *   而一个没有任何真实数字支撑的约束，只是一句宣传语。
 *
 * ── 它**不靠地图**，只靠问上游 ────────────────────────────────
 *   直觉上"选几个附近的城市"该用地理距离判断。实测直接推翻了这条路：
 *     珠海 ↔ 汕头：同省，直线约 300km  → 3 小时**到不了**（要绕广州）
 *     广州 ↔ 桂林：跨省，约 400km      → **124 分钟直达**
 *   地理近 ≠ 交通近，而"能不能到"只有上游知道。
 *   所以我们把"有没有这么快的走法"整件事外包给 `search-train`：
 *     `--total-duration-hour N` 是**服务端时长筛选**（和酒店那边的 `--hotel-stars` 同一模式）。
 *
 * ── 三个实测结论，每个都直接决定了下面的写法 ──────────────────
 *   ① **筛选是按"单段"算的，不是按往返合计。**
 *      实测 `--total-duration-hour 1`（=60 分）查广州↔珠海，仍然返回了 53 分 + 50 分的往返
 *      （合计 103 分，远超 60）。所以它是"每一段都要 ≤N"，正好是我们要的语义。
 *   ② **"没有这种走法"是一个明确的回答，不是故障。**
 *      `{ "status": 1, "message": "行程时长过滤结果为空" }` —— 上游在用 status=1 说一句结论。
 *      这条实测来自珠海↔桂林。③ 见下。
 *   ③ **退出码恒为 0**，在①②两种情况下都是。所以判据只能是信封里的 status + message，
 *      这已经是这个工程里第三次踩同一个形状的坑了（见 CandidateNormalizer.itemListOf）。
 *
 * ── 这个类里最重要的一条界线 ──────────────────────────────────
 *   **"没有 3 小时内的直达"和"车次查询失败了"必须给出完全相反的结论。**
 *     前者 → 告诉用户"这段路带娃会太累，换个目的地"；
 *     后者 → 告诉用户"暂时查不到车次，行程照常，交通信息缺一行"。
 *   把后者显示成前者，会让用户**永远放弃一条其实存在的路线**——
 *   这是我们最不能犯的错，因为它不报错、只是安静地把选择拿掉了。
 *   所以 {@link Outcome} 里 NO_EDGE 和 UPSTREAM_ERROR 是两个不同的值，
 *   而且**绝不共用一句提示文案**。
 */
@Component
public class LegService {

    private static final Logger log = LoggerFactory.getLogger(LegService.class);

    /**
     * 一段查询自己的时间预算。
     *
     * 为什么是 20 秒 —— 它是**算**出来的，不是拍的。一次 /generate 的时间构成：
     *   检索层最多 30 秒（PlanRules.DEFAULT.budgetMs）+ 模型排行程实测 25~60 秒 + 两段车次。
     * 前两项已经能到 90 秒，所以留给车次的总预算必须控制在 30 秒左右（前端超时 180 秒，见 http.ts）。
     * 20 秒刚好够一次"被限流 → 等 15 秒 → 重试成功"（单次 CLI 实测 1.3~2.7 秒），
     * 又不会让两段加起来吃掉一整分钟。
     *
     * ⚠️ 这和检索层的 30 秒是**两个预算**，不是同一个：那边超时的代价是"整份行程排不出来"，
     *    这边超时的代价只是"行程里少一行交通"。代价不同，愿意等的时间就该不同。
     */
    private static final long LEG_BUDGET_MS = 20_000;

    /**
     * 去程可接受的发车时段。
     *
     * 为什么要有这个 —— "带娃出行"在时间上的具体含义就是**不坐红眼、不要太早**。
     * 上游没有"上午出发"这种筛选项，但它的返回里**本来就带着时刻**，
     * 所以在已经拿到的结果里挑一条，是**零额外调用**的。
     * （这就是"凡是能靠本地重算解决的，就绝不花一次上游调用"那条原则的又一次应用。）
     *
     * 为什么是 07:00~13:00：早于 7 点出门对孩子太狠；晚于 13 点出发，
     * 到了就是傍晚，第一天整天废掉 —— 而对 3~6 天的行程，第一天废掉是很显眼的损失。
     */
    private static final LocalTime OUTBOUND_EARLIEST = LocalTime.of(7, 0);
    private static final LocalTime OUTBOUND_LATEST = LocalTime.of(13, 0);

    /**
     * 回程可接受的**发车**下界：不早于 13:00。
     *
     * ── 它和去程那两条是同一件事的两面 ────────────────────────────
     *   去程 `不晚于 13:00 发车`，防的是"到了就是傍晚，第一天整天废掉"；
     *   回程 `不早于 13:00 发车`，防的是"上午就得往车站赶，最后一天整天废掉"。
     *   两头守的都是"**别白扔一整天**"——对 3~6 天的行程，白扔整天是很显眼的损失。
     *
     * ── 为什么这条下界是**必需**的，不是锦上添花 ──────────────────
     *   在这条下界存在之前，回程只判一个上界（到达 ≤ 20:00）。而候选列表是**按时长升序**
     *   排的（`--sort-type 4`），于是"第一条满足上界的"往往就是**最早**的那一班 ——
     *   因为短途线路里最快的那趟常常正是首班车。
     *   实测的两段路把这件事的两面都演了一遍：
     *     广州→深圳：最快 29 分的是 **06:18** 发车 → 规则选中它，用户看到"凌晨六点出发"
     *     珠海→广州：最快 50 分的是 **18:13** 发车 → 同一条错规则选中它，**结果看起来是对的**
     *   后一例才是真正要记的：**同一个错规则在一段路上"碰对"了**，
     *   所以只测过珠海那一段的话，根本发现不了它。
     *   判据必须自己站得住，不能指望数据分布替你兜住。
     */
    private static final LocalTime RETURN_EARLIEST = LocalTime.of(13, 0);

    /**
     * 回程最晚到家时间。
     *
     * 和去程用同一个判据（别让孩子太累），但方向相反：去程怕太晚**到**，回程怕太晚**到家**。
     * 20:00 之后进门，洗漱完就 21 点多了 —— 那是把最后一天的疲劳带到第二天上学。
     *
     * ⚠️ 光有这一条上界是**不够**的（见 RETURN_EARLIEST 那段实测）：上界单独成立时，
     *    "满足上界"会被"最快的那班"挤成"最早的那班"。
     *    回程必须同时有下界，才是"**下午再走、但别太晚到家**"这个真实意图。
     */
    private static final LocalTime RETURN_ARRIVE_BY = LocalTime.of(20, 0);

    /**
     * 发到上游的时长粗筛窗口的**下界**（小时）。
     *
     * ── 它为什么不能跟着 maxLegMinutes 走 ─────────────────────────
     *   这个参数原来就是 `hoursCeiling(maxLegMinutes)`（180 分钟 → 3 小时），
     *   意图是"让上游粗筛、红线在本地判"。它有一个致命副作用：
     *   **当一段路最快的班次也超过窗口时，上游会返回
     *   `{"status":1,"message":"行程时长过滤结果为空"}` —— 于是我们连"最快的那班"
     *   都拿不到，整条路线被静默拿掉。**
     *
     *   实测（2026-11-17，深圳 → 上海 9 天行程）：`--total-duration-hour 3` 返回空，
     *   用户拿到的行程里**一行交通信息都没有**，而 warnings 写着
     *   "深圳 到 上海 在 180 分钟内没有直达车次" —— 听起来是一个很确定的结论，
     *   而深圳 → 上海的高铁是存在的（约 6.5 小时）。**这就是一句假话，
     *   而且用户会因此放弃这趟行程。**
     *
     *   所以窗口必须宽到"能拿到最快的那班"，红线才真正落在本地那条
     *   `minutes <= maxLegMinutes()` 上。12 小时覆盖国内所有主要城市对：
     *   深圳 → 上海 7h、广州 → 北京 8h、上海 → 成都 11h。
     *
     * ── 拿到超限的班次之后怎么办 ──────────────────────────────────
     *   **给它，并且明说超限**（见 {@link Leg#overLimit}）。这和 {@link #pick} 里
     *   "一条都不满足时段就退回第一条"是同一条原则：
     *   给一份真实但不理想的结果，永远好过给一句干脆的错话。
     */
    static final int MIN_SEARCH_WINDOW_HOURS = 12;

    /** 契约里的日期时间格式：ISO 到分钟。秒是上游的噪声，不要带出去 */
    private static final DateTimeFormatter ISO_MINUTES = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    /** 往哪个方向走。它只影响"挑哪一班"，不影响查询条件里的起终点（那是调用方给的） */
    public enum Direction {
        /** 去程：怕太晚到 → 挑 07:00~13:00 发车的 */
        OUTBOUND,
        /**
         * 回程：怕最后一天废掉、也怕太晚到家 → 挑 13:00 之后发车、且 20:00 前到的。
         *
         * 两头都要判。只判"20:00 前到"会让"最快的首班车"顺手胜出（见 RETURN_EARLIEST 的实测）。
         */
        RETURN
    }

    /**
     * 这一段查出来了什么。
     *
     * ⚠️ NO_EDGE 与 UPSTREAM_ERROR 的区别是整个类最要紧的地方（见类注释最后一节）。
     *    调用方**必须**对这两个分支给出不同的话，共用一句就等于把这条界线抹掉了。
     */
    public enum Outcome {
        /** 有真实车次 */
        FOUND,
        /** 上游明确回答：这个时长上限内没有走法。**这是结论，可以直接告诉用户** */
        NO_EDGE,
        /** 查询本身失败（超时 / 限流 / 参数被拒 / 上游改格式）。**不能推断没有走法** */
        UPSTREAM_ERROR
    }

    /**
     * @param transfer  只有 FOUND 时非 null
     * @param query     真实发出去的那条命令 —— 排障时最常见的误判是"我以为我查的是 A"
     * @param detail    失败或"没走法"时的一句话，给日志和 warnings 用
     * @param overLimit **这趟车是真的，但它超过了 maxLegMinutes。** 只在 FOUND 时可能为 true。
     *
     *   ── 为什么它不是一个 Outcome（而是一个附加标记）──────────────
     *     "有车但太长"和"没有这种走法"必须是两个不同的结论，因为调用方要说
     *     完全不同的话：前者是"给你留了最快的一班，但它要 X 小时"，
     *     后者才是"这段路没有走法，换个目的地吧"。
     *
     *     混成一个的后果实测过（2026-11-17，深圳 → 上海 9 天）：上游按粗筛窗口
     *     返回"结果为空"，我们把整条路线判成"没有走法"，用户拿到一份
     *     **一行交通都没有**的行程，warnings 还写着"在 180 分钟内没有直达车次" ——
     *     而高铁其实存在，约 6.5 小时。**一句听起来很确定的假话，比什么都不说更伤人。**
     *
     *   ⚠️ 但它**不能**直接变成 Outcome.NO_EDGE —— 那会让 `transfer` 变成 null，
     *      用户就又看不到那趟车了。真相是"有车"，就该把车给他。
     */
    public record Leg(Outcome outcome, Transfer transfer, String query, String detail, boolean overLimit) {
    }

    private final FlyaiCaller caller;
    private final CandidateNormalizer normalizer;
    private final FlyaiProperties props;

    public LegService(FlyaiCaller caller, CandidateNormalizer normalizer, FlyaiProperties props) {
        this.caller = caller;
        this.normalizer = normalizer;
        this.props = props;
    }

    /**
     * 查一段。
     *
     * @param date      出发日期（ISO）。传 null 也能查，但那样价格是参考价、班次也不对应当天
     * @param direction 只决定挑哪一班
     */
    public Leg search(String from, String to, String date, Direction direction) {
        String origin = requireCity(from, "出发地");
        String destination = requireCity(to, "目的地");
        if (origin.equals(destination)) {
            throw new IllegalArgumentException(
                    "出发地和目的地是同一座城市（" + origin + "）—— 同城不需要城际交通，调用方应该先判掉");
        }

        List<String> args = args(origin, destination, date, searchWindowHours());
        String query = "flyai " + String.join(" ", args);

        CandidateNormalizer.Normalized<FlyaiTrain> normalized;
        try {
            String raw = caller.call(args, Deadline.after(LEG_BUDGET_MS)).raw();
            normalized = normalizer.trains(raw);
        } catch (FlyaiException e) {
            if (e.reason() == FlyaiException.Reason.NO_RESULT) {
                // 上游的"这个时长内没有走法"——这是**结论**，不是失败。
                // ⚠️ 说"没有走法"时必须带上**真正查过的窗口**（现在是 searchWindowHours，
                //    不再是 maxLegMinutes）：否则用户会以为我们只查了 3 小时就下结论。
                return noEdge(origin, destination, query,
                        "在 " + searchWindowHours() + " 小时内没有查到直达走法");
            }
            log.warn("{} → {} 的车次查询失败（{}）：{}", origin, destination, e.reason(), e.getMessage());
            return new Leg(Outcome.UPSTREAM_ERROR, null, query, e.getMessage(), false);
        }

        List<FlyaiTrain> within = new ArrayList<>();
        for (FlyaiTrain train : normalized.items()) {
            Integer minutes = minutesOf(train.totalDuration());
            if (minutes != null && minutes <= maxLegMinutes()) {
                within.add(train);
            }
        }
        if (within.isEmpty()) {
            // ⭐ 两种情形**天差地别**，绝不能混成一句：
            //   ① 一条都没查到        → 真的没有这条路线；
            //   ② 有候选、但最快的也超上限 → **有车，只是太长**。
            //   把 ② 说成"没有走法"（这是加 MIN_SEARCH_WINDOW_HOURS 之前的行为）会让用户
            //   永远放弃一条其实存在的路线，而且全程不报任何错。
            return overLimitOrNone(origin, destination, query, searchWindowHours(), maxLegMinutes(),
                    normalized.items(), direction);
        }

        FlyaiTrain chosen = pick(within, direction);
        Transfer transfer = toTransfer(chosen, origin, destination);
        log.info("{} → {} {}：{} {} → {} {} 分钟 ¥{}",
                origin, destination, direction, transfer.trainNo(),
                transfer.depStation(), transfer.arrStation(),
                transfer.durationMinutes(), transfer.priceYuan());
        return new Leg(Outcome.FOUND, transfer, query, null, false);
    }

    /**
     * 上限之内一班都没有时，把这件事分成两个**不同**的结论。
     *
     * ①② 为什么要分开，见调用点。这里只说 ② 为什么是"给车"而不是"给一句更强的警告"：
     *   用户的真实约束是"带娃别太累"，不是"绝对不能超过 N 分钟"。
     *   给他一班真实的、最快的车（外加一句"它超了上限"），他就有了做决定所需的全部信息；
     *   只给一句"没有直达"，他拿到的是一个**假的选择集**。
     *
     * 参数化 windowHours / limitMinutes 只是为了能被单测直接钉住 —— 不碰网络、
     * 不碰配置，给得进候选就出得来结论。
     */
    static Leg overLimitOrNone(String origin, String destination, String query,
                               long windowHours, int limitMinutes,
                               List<FlyaiTrain> items, Direction direction) {
        if (items.isEmpty()) {
            return noEdge(origin, destination, query, windowHours + " 小时内没有查到直达走法");
        }
        FlyaiTrain fastest = pick(items, direction);
        Transfer transfer = toTransfer(fastest, origin, destination);
        if (transfer == null) {
            // 上游偶尔会回 journeys 为空的脏数据（见 toTransfer）。这时**不能**假装有车 ——
            // 一个字段全空的 Transfer 比"没查到"更糟：它会在界面上画出一行看起来正常的交通。
            return noEdge(origin, destination, query, "查到的车次数据不完整，没能取到可用的一班");
        }
        log.info("{} → {} {}：最快的一班也超过上限（{} > {} 分钟），仍然给出 {}",
                origin, destination, direction, transfer.durationMinutes(), limitMinutes,
                transfer.trainNo());
        return new Leg(Outcome.FOUND, transfer, query, null, true);
    }

    private static Leg noEdge(String origin, String destination, String query, String reason) {
        String detail = origin + " 到 " + destination + " " + reason;
        log.info("{}", detail);
        return new Leg(Outcome.NO_EDGE, null, query, detail, false);
    }

    /* ============================================================
     * 查询条件与挑选 —— 全是纯函数，单独抽出来就是为了能测
     * ============================================================ */

    /**
     * 见类注释 ①：`--total-duration-hour` 按**单段**筛，正是我们要的语义。
     *
     * ⚠️ 传进来的 {@code windowHours} 是**粗筛窗口**，不是那条上限（见
     *    {@link #MIN_SEARCH_WINDOW_HOURS}）。两者故意不是同一个数：
     *    窗口要是等于上限，一段"最快班次也超上限"的路会连候选都拿不到，
     *    于是我们只能对用户说"没有这种走法" —— 一句听起来很确定的假话。
     */
    static List<String> args(String origin, String destination, String date, long windowHours) {
        List<String> args = new ArrayList<>(List.of(
                "search-train", "--origin", origin, "--destination", destination));
        if (date != null && !date.isBlank()) {
            args.add("--dep-date");
            args.add(date);
        }
        args.add("--total-duration-hour");
        args.add(String.valueOf(windowHours));
        // 时长升序（实测有效）。挑"上午出发"那件事不在上游做 —— 见 pick 的注释：
        // 上游没有时段筛选这项能力，而我们拿回来的结果里本来就带着时刻。
        args.add("--sort-type");
        args.add("4");
        return args;
    }

    /**
     * 把分钟上限换算成上游要的**小时**（向上取整）。
     *
     * 上游这个参数是整数小时，所以 150 分钟只能表达成 3 小时（=180 分钟）。
     * 取整方向**必须向上**：向下取整会把红线收紧成 150 → 2 小时 = 120 分钟，
     * 那就是"配置说 150、实际只放 120"—— 一个不报错的谎。
     * 包级静态是为了能被单测钉住：这个方向的取整错了，用户不会看到任何报错，
     * 只会发现"我配了 150，可 130 分钟的车次总是查不到"。
     *
     * ⚠️ 它**不再直接决定发出去的参数**（那是 {@link #searchWindowHours}），
     *    只作为那个窗口的下界之一。理由见 {@link #MIN_SEARCH_WINDOW_HOURS}。
     */
    static long hoursCeiling(int maxLegMinutes) {
        return Math.max(1, (long) Math.ceil(maxLegMinutes / 60.0));
    }

    /**
     * 发到上游的时长粗筛窗口（小时）= max(配置换算出的小时, {@link #MIN_SEARCH_WINDOW_HOURS})。
     *
     * 取 max 而不是直接写死 12：用户要是把 maxLegMinutes 调到 20 小时，
     * 我们应该真的去查 20 小时，而不是被那个常量卡住。
     */
    static long searchWindowHours(int maxLegMinutes) {
        return Math.max(hoursCeiling(maxLegMinutes), MIN_SEARCH_WINDOW_HOURS);
    }

    private long searchWindowHours() {
        return searchWindowHours(maxLegMinutes());
    }

    private int maxLegMinutes() {
        return props.maxLegMinutes();
    }

    /**
     * 本层当前生效的单段时长上限（分钟）。
     *
     * 给调用方写"这趟车超了上限"那条警告用的 —— 那个数字在配置里，
     * 而"超了多少"只有拿它和 Transfer.durationMinutes 比才说得出来。
     */
    public int limitMinutes() {
        return maxLegMinutes();
    }

    /**
     * 从已拿到的候选里挑一班。
     *
     * 两件事按顺序发生：
     *   ① **上游已经按"时长升序"排好了**，所以"第一条满足时段要求的"就是"满足时段要求里最快的"——
     *      不需要再排一次，也不需要遍历全部求最小值。
     *   ② 时段要求见 OUTBOUND_EARLIEST / RETURN_ARRIVE_BY。
     *
     * ⚠️ 一条候选都不满足时段时，**退回第一条而不是返回 null**。
     *    理由是两者的代价差太远：退回去，用户拿到一班时刻不理想的真实车次，他不满意可以自己改；
     *    返回 null，用户会看到"没有车次" —— 一句**假话**，而且他会因此放弃这趟行程。
     *    "给一份真实但不理想的结果"始终优先于"给一句听起来干脆的错话"，
     *    这条在酒店星级筛那边已经用过一次（筛空要能退回去）。
     *
     * 纯函数：给得进列表就出得来结果，不碰网络也不碰配置 —— 所以时段规则能被单测钉住。
     */
    static FlyaiTrain pick(List<FlyaiTrain> candidates, Direction direction) {
        for (FlyaiTrain train : candidates) {
            if (acceptable(train, direction)) {
                return train;
            }
        }
        return candidates.get(0);
    }

    /**
     * 这一班落在可接受的时段窗口里吗。
     *
     * ⚠️ 两个方向的判据**不是对称地各看一个时刻**，这点容易写错：
     *   去程看**发车**（怕太晚到 → 窗口卡在发车）；
     *   回程要看**两个**（下午才走 → 卡发车；别太晚到家 → 卡到达）。
     *   只给回程留一个上界，就是"凌晨六点的返程车"那个 bug 的全部成因。
     *
     * 时刻读不出来 → false（不参与时段判断），但**不因此排除整批** ——
     * 调用方的 for 会继续往后找，一条都找不到才走退回第一条那条路。
     */
    static boolean acceptable(FlyaiTrain train, Direction direction) {
        LocalDateTime depAt = departureAt(train);
        if (depAt == null) {
            return false;
        }
        LocalTime dep = depAt.toLocalTime();
        if (direction == Direction.OUTBOUND) {
            return !dep.isBefore(OUTBOUND_EARLIEST) && !dep.isAfter(OUTBOUND_LATEST);
        }

        LocalDateTime arrAt = arrivalAt(train);
        if (arrAt == null) {
            return false;
        }
        // ⭐ 跨午夜的到达**一律不合格** —— 这一条必须比**日期**，不能只比时刻。
        //
        //    实测（2026-11-17，上海 → 深圳回程，12 小时窗口）：候选里那趟 20:15 发的过夜车
        //    次日 07:03 到。只看时刻的话，它"早上 7 点到家"——完美落在 RETURN_ARRIVE_BY 之内，
        //    于是这趟 **10 小时 48 分的夜车被判成"最合适的回程"**，而带娃坐一夜的代价
        //    它一个字都不会提。
        //
        //    这是这个工程反复出现的那类错的又一个实例：**规则对，取值方式错。**
        //    （`Transfer.arrDateTime` 早就带上了日期，正是为了这个 ——
        //      但 `pick` 这条路上没人用它，只看了一个 LocalTime。）
        if (arrAt.toLocalDate().isAfter(depAt.toLocalDate())) {
            return false;
        }
        return !dep.isBefore(RETURN_EARLIEST) && !arrAt.toLocalTime().isAfter(RETURN_ARRIVE_BY);
    }

    /**
     * 上游的一条 item → 契约里的 Transfer。
     *
     * ⚠️ 用 **first / last** 取起终点，不用 `segments[0]`：
     *    中转方案的 `segments` 有 N 个元素，第一个 segment 的**到达站是中转站**。
     *    用 `segments[0]` 的话，一趟"珠海→广州南→桂林西"会被显示成"珠海→广州南"，
     *    而且**不会报错** —— 用户看到的是一份把他送到了中转站就结束的行程。
     *    这是这个工程里第 N 次同一个形状的坑：错的是"取值方式"，不是数据。
     */
    static Transfer toTransfer(FlyaiTrain train, String from, String to) {
        if (train.journeys().isEmpty()) {
            return null;
        }
        FlyaiTrain.Journey journey = train.journeys().get(0);
        List<FlyaiTrain.Segment> segments = journey.segments();
        if (segments.isEmpty()) {
            return null;
        }
        FlyaiTrain.Segment first = segments.get(0);
        FlyaiTrain.Segment last = segments.get(segments.size() - 1);
        boolean direct = segments.size() == 1;
        return new Transfer(
                from,
                to,
                first.depStationShortName(),
                last.arrStationShortName(),
                isoMinutes(first.depDateTime()),
                isoMinutes(last.arrDateTime()),
                minutesOf(train.totalDuration()),
                CandidateNormalizer.parseYuan(train.price()),
                trainNoOf(first),
                first.seatClassName(),
                direct,
                // 中转站 = 第一段的到达站。直达时为 null（而不是空串）：
                // "没有中转站"和"中转站的名字是空的"不是一回事，而前端只用判一次 null。
                direct ? null : first.arrStationShortName(),
                train.jumpUrl()
        );
    }

    /** "动车" + "D947" → "动车D947"。上游是分开两个字段，而单看编号分不出高铁还是动车 */
    private static String trainNoOf(FlyaiTrain.Segment segment) {
        String type = segment.marketingTransportName() == null ? "" : segment.marketingTransportName().strip();
        String no = segment.marketingTransportNo() == null ? "" : segment.marketingTransportNo().strip();
        String combined = (type + no).strip();
        return combined.isEmpty() ? null : combined;
    }

    /** "53" → 53。读不出来回 null —— 而不是 0，因为 0 分钟的车程是个不存在的概念 */
    static Integer minutesOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * "2026-11-17 08:36:00" → "2026-11-17T08:36"。
     *
     * 上游用**空格**分隔日期和时间，而 ISO 用 'T' —— 所以这里先替换再解析。
     * 不用 DateTimeFormatter 写死 "yyyy-MM-dd HH:mm:ss"：那样上游哪天去掉秒，
     * 我们就会静默地拿到 null。替换 + 默认解析器能同时吃下"有秒"和"没秒"两种。
     */
    static String isoMinutes(String raw) {
        LocalDateTime parsed = parseDateTime(raw);
        return parsed == null ? null : parsed.format(ISO_MINUTES);
    }

    /**
     * 发车时刻 —— **带日期**，不是裸的 LocalTime。
     *
     * 为什么必须带日期：这一层要判"有没有跨午夜"，而跨午夜这件事**只比时刻是看不出来的**
     * （次日 07:03 和当天 07:03 的 LocalTime 完全相等）。见 {@link #acceptable} 里那段实测。
     */
    static LocalDateTime departureAt(FlyaiTrain train) {
        FlyaiTrain.Segment segment = firstSegment(train);
        return segment == null ? null : parseDateTime(segment.depDateTime());
    }

    /** 到达时刻 —— 同理带日期，且取**最后一段**的（中转时前一段的到达站只是换乘站） */
    static LocalDateTime arrivalAt(FlyaiTrain train) {
        FlyaiTrain.Segment segment = lastSegment(train);
        return segment == null ? null : parseDateTime(segment.arrDateTime());
    }

    private static FlyaiTrain.Segment firstSegment(FlyaiTrain train) {
        List<FlyaiTrain.Segment> segments = segmentsOf(train);
        return segments.isEmpty() ? null : segments.get(0);
    }

    private static FlyaiTrain.Segment lastSegment(FlyaiTrain train) {
        List<FlyaiTrain.Segment> segments = segmentsOf(train);
        return segments.isEmpty() ? null : segments.get(segments.size() - 1);
    }

    private static List<FlyaiTrain.Segment> segmentsOf(FlyaiTrain train) {
        if (train.journeys() == null || train.journeys().isEmpty()) {
            return List.of();
        }
        return train.journeys().get(0).segments();
    }

    private static LocalDateTime parseDateTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw.strip().replace(' ', 'T'));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * 城市名的守门，和检索层用的是同一套。
     *
     * 为什么必须在这里也挡一道：发出去的是 `--origin X --destination Y`，
     * 而"查不到"和"名字是错的"在响应上**完全一样**——
     * 也就是说，一个写错的出发地会被解读成"这两个城市之间没有车次"，
     * 用户看到的是"没有这种走法"这个**假结论**。
     * 挡在我们能确认的地方，代价只是拒绝一次查询；放过去的代价是一条被静默拿掉的路线。
     */
    private static String requireCity(String raw, String role) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(role + "不能为空 —— 不知道从哪到哪，车次查不了");
        }
        String city = raw.strip();
        if (!CityNames.isSingleCity(city)) {
            throw new IllegalArgumentException(role + "「" + city + "」不是一个具体的城市名。" + CityNames.hint());
        }
        return city;
    }
}
