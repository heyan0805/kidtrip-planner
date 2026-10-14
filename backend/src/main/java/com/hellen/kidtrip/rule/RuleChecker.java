package com.hellen.kidtrip.rule;

import com.hellen.kidtrip.candidate.HotelCandidate;
import com.hellen.kidtrip.candidate.PoiCandidate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 规则校验：把候选池过一遍规则，选出能用的，并**逐条记账**筛掉了谁、为什么。
 *
 * 它刻意只做"单条候选能不能用"和"去重"这两件事。
 * 需要拿到"顺序"才算得出来的约束（比如相邻景点距离）**不在这里** ——
 * 那是排期阶段的活。一个校验器要能一句话说清自己判的是什么，否则它会慢慢长成一个上帝类。
 */
@Component
public class RuleChecker {

    /**
     * 校验结果。
     * 注意 rejected 是"**最终**被筛掉的" —— 不是每一轮筛掉的累加。
     * 如果第二轮放宽之后某条又合格了，它就不该出现在这里（否则用户会看到一条"被筛掉"却明明在行程里的景点）。
     */
    public record CheckResult(
            List<PoiCandidate> pois,
            List<HotelCandidate> hotels,
            List<Rejected> rejected)
    {
        public int kept() {
            return pois.size() + hotels.size();
        }
    }

    public CheckResult check(List<PoiCandidate> pois, List<HotelCandidate> hotels, PlanRules rules) {
        List<Rejected> rejected = new ArrayList<>();
        List<PoiCandidate> keptPois = new ArrayList<>();
        List<HotelCandidate> keptHotels = new ArrayList<>();

        Set<String> seen = new HashSet<>();
        for (PoiCandidate p : pois) {
            String reason = rejectReason(p, rules);
            if (reason == null && !seen.add(dedupeKey(p))) {
                reason = "重复候选（稳定标识 " + dedupeKey(p) + " 已出现过）";
            }
            if (reason == null) {
                keptPois.add(p);
            } else {
                rejected.add(new Rejected(p.name(), reason));
            }
        }

        for (HotelCandidate h : hotels) {
            String reason = hotelRejectReason(h);
            if (reason == null && !seen.add(dedupeKey(h))) {
                reason = "重复候选（稳定标识 " + dedupeKey(h) + " 已出现过）";
            }
            if (reason == null) {
                keptHotels.add(h);
            } else {
                rejected.add(new Rejected(h.name(), reason));
            }
        }

        return new CheckResult(keptPois, keptHotels, rejected);
    }

    /** 返回 null = 通过；返回字符串 = 不合格的原因（这句话会直接给用户看，所以写成人话）。 */
    private static String rejectReason(PoiCandidate p, PlanRules rules) {
        if (p.stableId().isBlank()) {
            // 没有稳定 id 的候选在整条链路里无法追溯、无法去重 —— 而"每一条候选都能追回飞猪"
            // 是我们这个架构唯一不肯让步的东西（大模型只做选择，事实必须来自飞猪）。
            return "缺少景点 id，无法追溯到飞猪";
        }
        if (p.name().isBlank()) {
            return "没有名称";
        }
        Integer cap = rules.maxTicketPriceYuan();
        if (cap != null && p.priceYuan() != null && p.priceYuan() > cap) {
            return "票价 ¥" + trimZero(p.priceYuan()) + " 超过上限 ¥" + cap;
        }
        // ⚠️ 上面那个判断里 `p.priceYuan() != null` 不是多余的：
        //    priceYuan == null 的含义是"这张票飞猪上没有"，可能是免费公园，也可能是含在母景区里。
        //    要是漏掉这个判空，写成 `cap != null && p.priceYuan() > cap`，就是 NPE；
        //    要是写成 `p.priceYuan() == null → 不合格`，所有免费公园会被一次杀光。
        //    两种写法都是 bug，而且都是"看起来更简洁"的那种。
        Integer minLevel = rules.minPoiLevel();
        if (minLevel != null && p.level() != null && p.level() < minLevel) {
            return "等级 " + p.level() + " 低于要求 " + minLevel;
        }
        return null;
    }

    /**
     * 酒店不套用门票价格上限。
     * 理由：那个字段的语义是"门票"，酒店是房价，两者不是一个量纲；
     * 而酒店的价格筛选应该走**服务端参数** `--max-price`（飞猪支持），
     * 让上游只返回符合条件的结果，比拉回来一堆再本地筛更省。
     */
    private static String hotelRejectReason(HotelCandidate h) {
        if (h.stableId().isBlank()) {
            return "缺少酒店 id，无法追溯到飞猪";
        }
        if (h.name().isBlank()) {
            return "没有名称";
        }
        return null;
    }

    /**
     * 去重键。
     * 稳定 id 缺失时退化成按名字去重 —— 不是理想做法，但比"完全不去重"好：
     * 少了它，补查回来的那批会把宽查已有的同名条目再塞一遍，池子里出现重复景点。
     */
    private static String dedupeKey(com.hellen.kidtrip.candidate.Candidate c) {
        return c.stableId().isBlank() ? c.getKind() + ":" + c.name() : c.stableId();
    }

    /** 227.0 显示成 "227"，362.88 显示成 "362.88" —— 给用户看的数字不该拖着 .0 的尾巴 */
    private static String trimZero(Double d) {
        return d == Math.floor(d) ? String.valueOf(d.longValue()) : String.valueOf(d);
    }
}
