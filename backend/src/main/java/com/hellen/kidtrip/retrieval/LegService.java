package com.hellen.kidtrip.retrieval;

import com.hellen.kidtrip.candidate.CandidateNormalizer;
import com.hellen.kidtrip.flyai.CityNames;
import com.hellen.kidtrip.flyai.FlyaiException;
import com.hellen.kidtrip.flyai.FlyaiProperties;
import com.hellen.kidtrip.flyai.raw.FlyaiLeg;
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
 *
 * ── 飞机（2026-10-14 加）───────────────────────────────────────
 *   同一个问题换一种载体问，规矩**一个字都没改**：同一套时长上限、同一套时段窗口
 *   （见 {@link #acceptable}）、同一个"失败与结论必须分开"的界线。
 *   所以它是这个类里的第二条路，不是第二个类 —— 两条路走同一段代码（{@code search(mode,…)}），
 *   差别只有三处，全是"上游逼出来的"，不是我们想出来的：
 *
 *     ① **参数不同**：火车可以发 `--total-duration-hour`（服务端粗筛），
 *        机票发它会被上游 **400 拒绝**（`Argument not allowed: [totalDurationHour]` ——
 *        `--help` 里列了这个参数，但服务端不认。**help 不是契约**）。
 *        于是机票的时长红线**只能本地判**（{@link #withinLimit}），
 *        也没有"12 小时粗筛窗口"这回事 —— 那个窗口本来就是为绕开粗筛副作用而存在的。
 *     ② **只留直达**：机票发 `--journey-type 1`（实测有效）。不筛的话池子里混着
 *        630 分钟（22:00 起飞、次日 08:30 落）的经停航班，而**最便宜的那条恰好是最慢的那条** ——
 *        不筛时长，"按价格排第一"就是个陷阱。
 *     ③ **价格字段名不同**：火车 `price`、机票 `ticketPrice`（见 CandidateNormalizer.parsePrice）。
 *
 *   ⚠️ 什么时候查飞机**不在这里决定**（这里只提供能力）。见 PlanService.searchLeg：
 *      它只在"火车这条腿带娃偏累"时补查一次 —— 每一次上游调用都是额度，
 *      而"这段路飞机会不会更合适"这个问题，先由那条 180 分钟的红线替我们筛掉了绝大多数情况。
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
     * 查一段**火车**。
     *
     * @param date      出发日期（ISO）。传 null 也能查，但那样价格是参考价、班次也不对应当天
     * @param direction 只决定挑哪一班
     */
    public Leg search(String from, String to, String date, Direction direction) {
        return search(TransportMode.TRAIN, from, to, date, direction);
    }

    /**
     * 查一段**航班** —— 同一段路的另一种走法。
     *
     * 调用方问它之前先想清楚"为什么值得花这一次调用"：见类注释末尾（只有"火车这条腿
     * 带娃偏累"时才查）。这里不设门禁，是因为**判据属于调用方**（它才知道自己已经知道什么）。
     *
     * ⚠️ 传进来的 date 必须是**同一天** —— 飞机和火车若不在同一天，两行数字就没法并排看，
     *    而并排看正是查它的全部目的。
     */
    public Leg searchFlight(String from, String to, String date, Direction direction) {
        return search(TransportMode.FLIGHT, from, to, date, direction);
    }

    private Leg search(TransportMode mode, String from, String to, String date, Direction direction) {
        String origin = requireCity(from, "出发地");
        String destination = requireCity(to, "目的地");
        if (origin.equals(destination)) {
            throw new IllegalArgumentException(
                    "出发地和目的地是同一座城市（" + origin + "）—— 同城不需要城际交通，调用方应该先判掉");
        }

        List<String> args = args(mode, origin, destination, date, searchWindowHours());
        String query = "flyai " + String.join(" ", args);

        CandidateNormalizer.Normalized<FlyaiLeg> normalized;
        try {
            String raw = caller.call(args, Deadline.after(LEG_BUDGET_MS)).raw();
            normalized = parse(mode, raw);
        } catch (FlyaiException e) {
            if (e.reason() == FlyaiException.Reason.NO_RESULT) {
                // 上游的"这个时长内没有走法"——这是**结论**，不是失败。
                // ⚠️ 说"没有走法"时必须带上**真正查过的窗口**（现在是 searchWindowHours，
                //    不再是 maxLegMinutes）：否则用户会以为我们只查了 3 小时就下结论。
                // ⚠️ 机票那条路走不到这里（它没发时长筛选，见 flights 的注释）。
                return noEdge(origin, destination, query, noEdgeReason(mode, searchWindowHours()));
            }
            log.warn("{} → {} 的{}查询失败（{}）：{}", origin, destination,
                    mode.label(), e.reason(), e.getMessage());
            return new Leg(Outcome.UPSTREAM_ERROR, null, query, e.getMessage(), false);
        }

        List<FlyaiLeg> within = withinLimit(normalized.items());
        if (within.isEmpty()) {
            // ⭐ 两种情形**天差地别**，绝不能混成一句：
            //   ① 一条都没查到        → 真的没有这条路线；
            //   ② 有候选、但最快的也超上限 → **有走法，只是太长**。
            //   把 ② 说成"没有走法"（这是加 MIN_SEARCH_WINDOW_HOURS 之前的行为）会让用户
            //   永远放弃一条其实存在的路线，而且全程不报任何错。
            return overLimitOrNone(origin, destination, query, mode, searchWindowHours(),
                    maxLegMinutes(), normalized.items(), direction);
        }

        FlyaiLeg chosen = pick(within, direction);
        Transfer transfer = toTransfer(chosen, origin, destination, mode);
        log.info("{} → {} {}：{} {} {} → {} {} 分钟 ¥{}",
                origin, destination, direction, mode.label(), transfer.serviceNo(),
                transfer.depStation(), transfer.arrStation(),
                transfer.durationMinutes(), transfer.priceYuan());
        return new Leg(Outcome.FOUND, transfer, query, null, false);
    }

    /**
     * 从候选里挑出时长在上限之内的那批。**纯本地重算，零额外调用。**
     *
     * 火车那边这件事是**双保险**（上游已按小时粗筛过一遍），机票那边它是**唯一那道闸** ——
     * 上游对机票不接受时长参数，所以一切都在这里判。
     */
    private List<FlyaiLeg> withinLimit(List<FlyaiLeg> items) {
        List<FlyaiLeg> within = new ArrayList<>();
        for (FlyaiLeg leg : items) {
            Integer minutes = minutesOf(leg.totalDuration());
            if (minutes != null && minutes <= maxLegMinutes()) {
                within.add(leg);
            }
        }
        return within;
    }

    /** 载体对应的解析方法。两个方法的差别只有 status!=0 的处置，见 CandidateNormalizer */
    private CandidateNormalizer.Normalized<FlyaiLeg> parse(TransportMode mode, String raw) {
        return mode == TransportMode.FLIGHT ? normalizer.flights(raw) : normalizer.trains(raw);
    }

    /**
     * 查不到时那句话里的"为什么"。
     *
     * 为什么要分开写：火车那边"12 小时内没有走法"里的 12 **确实是我们发出去的窗口**，
     * 而机票根本没发过窗口 —— 把同一句话套给机票，就等于编了一个我们没做过的动作。
     * （虽然机票走不到这一支，但那是因为上游的行为，不是因为这句话对。）
     */
    static String noEdgeReason(TransportMode mode, long windowHours) {
        if (mode == TransportMode.FLIGHT) {
            return "没有查到可用的直达航班";
        }
        return windowHours + " 小时内没有查到直达走法";
    }

    /**
     * 上限之内一班都没有时，把这件事分成两个**不同**的结论。
     *
     * ①② 为什么要分开，见调用点。这里只说 ② 为什么是"给走法"而不是"给一句更强的警告"：
     *   用户的真实约束是"带娃别太累"，不是"绝对不能超过 N 分钟"。
     *   给他一班真实的、最快的（外加一句"它超了上限"），他就有了做决定所需的全部信息；
     *   只给一句"没有直达"，他拿到的是一个**假的选择集**。
     *
     * 参数化 windowHours / limitMinutes / mode 只是为了能被单测直接钉住 —— 不碰网络、
     * 不碰配置，给得进候选就出得来结论。
     */
    static Leg overLimitOrNone(String origin, String destination, String query, TransportMode mode,
                               long windowHours, int limitMinutes,
                               List<FlyaiLeg> items, Direction direction) {
        if (items.isEmpty()) {
            return noEdge(origin, destination, query, noEdgeReason(mode, windowHours));
        }
        FlyaiLeg fastest = pick(items, direction);
        Transfer transfer = toTransfer(fastest, origin, destination, mode);
        if (transfer == null) {
            // 上游偶尔会回 journeys 为空的脏数据（见 toTransfer）。这时**不能**假装有 ——
            // 一个字段全空的 Transfer 比"没查到"更糟：它会在界面上画出一行看起来正常的交通。
            return noEdge(origin, destination, query, "查到的"
                    + mode.label() + "数据不完整，没能取到可用的一班");
        }
        log.info("{} → {} {}：{}里最快的一班也超过上限（{} > {} 分钟），仍然给出 {}",
                origin, destination, direction, mode.label(), transfer.durationMinutes(), limitMinutes,
                transfer.serviceNo());
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
     * 拼发出去的参数。**两种载体在这里分叉，而且分得刻意。**
     *
     * 见类注释 ①：`--total-duration-hour` 按**单段**筛，正是我们要的语义。
     *
     * ⚠️ 传进来的 {@code windowHours} 是**粗筛窗口**，不是那条上限（见
     *    {@link #MIN_SEARCH_WINDOW_HOURS}）。两者故意不是同一个数：
     *    窗口要是等于上限，一段"最快班次也超上限"的路会连候选都拿不到，
     *    于是我们只能对用户说"没有这种走法" —— 一句听起来很确定的假话。
     *
     * ⚠️ 但**机票不发这个参数**：上游对它回 400（`Argument not allowed: [totalDurationHour]`），
     *    实测确认 —— `--help` 里明明列着它。所以：
     *      火车：上游粗筛 12 小时 + 本地按 maxLegMinutes 判（双保险，窗口是为了不被粗筛误杀）
     *      机票：上游不筛 + 本地按 maxLegMinutes 判（唯一那道闸），改成 `--journey-type 1` 只留直达
     *    两条路的差别是**上游能力**造成的，不是产品规则不同 —— 红线始终只有一条，在本地。
     */
    static List<String> args(TransportMode mode, String origin, String destination, String date,
                             long windowHours) {
        List<String> args = new ArrayList<>(List.of(
                mode == TransportMode.FLIGHT ? "search-flight" : "search-train",
                "--origin", origin, "--destination", destination));
        if (date != null && !date.isBlank()) {
            args.add("--dep-date");
            args.add(date);
        }
        if (mode == TransportMode.FLIGHT) {
            // 只留直达。不加的话池子里会有 630 分钟的经停航班，而且**最便宜的那条往往最慢** ——
            // 那样"按时长升序取第一条"就变成了"取一条又贵又慢的"。
            args.add("--journey-type");
            args.add("1");
        } else {
            args.add("--total-duration-hour");
            args.add(String.valueOf(windowHours));
        }
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
    static FlyaiLeg pick(List<FlyaiLeg> candidates, Direction direction) {
        for (FlyaiLeg leg : candidates) {
            if (acceptable(leg, direction)) {
                return leg;
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
    static boolean acceptable(FlyaiLeg leg, Direction direction) {
        LocalDateTime depAt = departureAt(leg);
        if (depAt == null) {
            return false;
        }
        LocalTime dep = depAt.toLocalTime();
        if (direction == Direction.OUTBOUND) {
            return !dep.isBefore(OUTBOUND_EARLIEST) && !dep.isAfter(OUTBOUND_LATEST);
        }

        LocalDateTime arrAt = arrivalAt(leg);
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
     *
     * ⚠️ `mode` 是从**我们查了哪个子命令**传进来的，**不从 `segment.transportType()` 读**。
     *    理由见 TransportMode：我们知道得比下游字段更早、更可靠。
     */
    static Transfer toTransfer(FlyaiLeg leg, String from, String to, TransportMode mode) {
        if (leg.journeys().isEmpty()) {
            return null;
        }
        FlyaiLeg.Journey journey = leg.journeys().get(0);
        List<FlyaiLeg.Segment> segments = journey.segments();
        if (segments.isEmpty()) {
            return null;
        }
        FlyaiLeg.Segment first = segments.get(0);
        FlyaiLeg.Segment last = segments.get(segments.size() - 1);
        boolean direct = segments.size() == 1;
        return new Transfer(
                from,
                to,
                mode,
                first.depStationShortName(),
                last.arrStationShortName(),
                isoMinutes(first.depDateTime()),
                isoMinutes(last.arrDateTime()),
                minutesOf(leg.totalDuration()),
                // ⚠️ 走统一的取价口，不要在这里写 leg.price()：机票的价格在 **ticketPrice** 里。
                //    写成 price() 不会报错，只会让所有航班都显示"票价未知"（看着还挺正常）。
                CandidateNormalizer.parsePrice(leg),
                serviceNoOf(first),
                first.seatClassName(),
                direct,
                // 中转站 = 第一段的到达站。直达时为 null（而不是空串）：
                // "没有中转站"和"中转站的名字是空的"不是一回事，而前端只用判一次 null。
                direct ? null : first.arrStationShortName(),
                leg.jumpUrl()
        );
    }

    /** "动车" + "D947" → "动车D947"；"深航" + "ZH9501" → "深航ZH9501"。
     *  上游是分开两个字段，而单看编号分不出高铁/动车，也分不出是哪家航司 */
    private static String serviceNoOf(FlyaiLeg.Segment segment) {
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
    static LocalDateTime departureAt(FlyaiLeg leg) {
        FlyaiLeg.Segment segment = firstSegment(leg);
        return segment == null ? null : parseDateTime(segment.depDateTime());
    }

    /** 到达时刻 —— 同理带日期，且取**最后一段**的（中转时前一段的到达站只是换乘站） */
    static LocalDateTime arrivalAt(FlyaiLeg leg) {
        FlyaiLeg.Segment segment = lastSegment(leg);
        return segment == null ? null : parseDateTime(segment.arrDateTime());
    }

    private static FlyaiLeg.Segment firstSegment(FlyaiLeg leg) {
        List<FlyaiLeg.Segment> segments = segmentsOf(leg);
        return segments.isEmpty() ? null : segments.get(0);
    }

    private static FlyaiLeg.Segment lastSegment(FlyaiLeg leg) {
        List<FlyaiLeg.Segment> segments = segmentsOf(leg);
        return segments.isEmpty() ? null : segments.get(segments.size() - 1);
    }

    private static List<FlyaiLeg.Segment> segmentsOf(FlyaiLeg leg) {
        if (leg.journeys() == null || leg.journeys().isEmpty()) {
            return List.of();
        }
        return leg.journeys().get(0).segments();
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
