package com.hellen.kidtrip.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * persona 合并 —— "每一轮对话都在更新对你的判断"这个机制的全部实现。
 *
 * 要钉住的是一句话：**同 key 覆盖，其余一个都不许丢。**
 *
 * 它和 TravelParamsMergeTest 是**故意相反**的两套规则，所以这两个测试文件
 * 值得对着看：
 *   画像（事实）  —— 模型没答的，保留原值。因为"没提城市"就是"没提城市"。
 *   推论（persona）—— 同 key 覆盖、其余保留。因为推论要能被推翻重来。
 * 两套规则混淆的后果都很静默：事实被退回旧值，用户会以为要重填；
 * 推论被永久粘住，用户会以为你懂他，其实你懂的是三个月前的他。
 */
@DisplayName("persona 合并：同 key 覆盖，没提到的保留")
class PersonaMergeTest {

    /** 只写这个用例关心的维度，三个参数都是位置参数，包一层免得插错顺序 */
    private static Persona.Inference i(String key, String value, String evidence) {
        return new Persona.Inference(key, value, evidence);
    }

    private static Persona of(Persona.Inference... items) {
        return Persona.of(List.of(items));
    }

    /** 聊了几轮之后的典型状态：两个维度都有判断，都带依据 */
    private static Persona current() {
        return of(
                i("walking", "少走路", "我想有一个放松的旅行"),
                i("budget", "想省着花", "预算别太高")
        );
    }

    @Test
    @DisplayName("这一轮什么都没推出来 → 原值一个都不动")
    void emptyPatchKeepsEverything() {
        assertEquals(current(), current().mergedWith(Persona.empty()));
    }

    @Test
    @DisplayName("patch 整个是 null → 返回原值，不抛 NPE")
    void nullPatchIsSafe() {
        assertEquals(current(), current().mergedWith(null));
    }

    @Test
    @DisplayName("⭐ 同 key 覆盖，连依据一起换 —— 这是'用户改主意了'的唯一表达方式")
    void sameKeyOverridesIncludingEvidence() {
        Persona patch = of(i("walking", "无所谓", "其实走走路也挺好的"));

        Persona merged = current().mergedWith(patch);

        assertEquals("无所谓", merged.valueOf("walking"));
        assertEquals("其实走走路也挺好的", merged.inferences().stream()
                        .filter(x -> x.key().equals("walking")).findFirst().orElseThrow().evidence(),
                "依据必须跟着换 —— 新依据更贴近用户现在的说法，留着旧的等于拿旧话证明新判断");
        assertEquals("想省着花", merged.valueOf("budget"), "别的维度不受影响");
        assertEquals(2, merged.size(), "覆盖不是新增，条数不变");
    }

    @Test
    @DisplayName("⭐ 没提到的维度一律保留 —— 模型这轮没提 ≠ 用户撤回了这个偏好")
    void untouchedTraitsSurvive() {
        // 这是整个类里最重要的一条。理由在 Persona 的类注释里写全了，
        // 这里把它钉成一个可执行的事实：
        // 保留错了 → 一条过时偏好影响一次排程；
        // 丢掉错了 → 用户前面聊的全白聊，而且他看不见。
        Persona patch = of(i("rest", "要午睡", "孩子中午要睡一会儿"));

        Persona merged = current().mergedWith(patch);

        assertEquals("少走路", merged.valueOf("walking"), "patch 里没有 walking，但它必须还在");
        assertEquals("想省着花", merged.valueOf("budget"));
        assertEquals("要午睡", merged.valueOf("rest"), "新维度追加");
        assertEquals(3, merged.size());
    }

    @Test
    @DisplayName("覆盖已存在的 key 不会把它挪到队尾 —— 顺序稳定")
    void overrideKeepsPosition() {
        // 顺序有意义：它决定卡片上条目的先后、以及 prompt 里那段的先后。
        // 用户熟悉的顺序不该因为某一条被更新而重排 ——
        // 那会让他在界面上"找不着刚才那条了"，还以为它被删了。
        Persona merged = current().mergedWith(of(i("budget", "价格不敏感", "这次想住好一点")));

        assertEquals(List.of("walking", "budget"),
                merged.inferences().stream().map(Persona.Inference::key).toList());
        assertEquals("价格不敏感", merged.valueOf("budget"));
    }

    @Test
    @DisplayName("多轮累积：每轮补一个维度，前面的都不丢")
    void accumulatesAcrossTurns() {
        Persona p = Persona.empty();
        p = p.mergedWith(of(i("walking", "少走路", "我想有一个放松的旅行")));
        p = p.mergedWith(of(i("rest", "要午睡", "孩子中午要睡一会儿")));
        p = p.mergedWith(of(i("budget", "想省着花", "预算别太高")));
        p = p.mergedWith(of(i("crowd", "避开人多", "别去人挤人的地方")));

        assertEquals(4, p.size());
        assertEquals("少走路", p.valueOf("walking"), "第一轮那条必须还在");
        assertEquals("避开人多", p.valueOf("crowd"));
    }

    @Test
    @DisplayName("中间有一轮什么都没推出来，也不影响累积")
    void emptyTurnsDoNotErode() {
        Persona p = current();
        p = p.mergedWith(Persona.empty());
        p = p.mergedWith(null);
        p = p.mergedWith(of(i("rest", "要午睡", "孩子中午要睡一会儿")));

        assertEquals(3, p.size());
        assertEquals("少走路", p.valueOf("walking"));
    }

    @Test
    @DisplayName("empty() 真的空，而且每次调用都相等（它是值，不是单例状态）")
    void emptyIsReallyEmpty() {
        assertTrue(Persona.empty().isEmpty());
        assertEquals(0, Persona.empty().size());
        assertEquals(Persona.empty(), Persona.empty());
        assertTrue(Persona.of(List.of()).isEmpty(), "空列表和 empty() 是同一个东西");
        assertTrue(Persona.of(null).isEmpty(), "null 也走同一条路，不要另开一个降级分支");
    }

    @Test
    @DisplayName("valueOf / has：查得到、查不到、大小写不敏感、给 null 不炸")
    void lookups() {
        Persona p = current();

        assertTrue(p.has("walking"));
        assertFalse(p.has("crowd"));
        assertNull(p.valueOf("crowd"), "没有这个维度 → null，不是空串");

        assertEquals("少走路", p.valueOf("WALKING"), "查表要大小写不敏感");
        assertEquals("少走路", p.valueOf("  walking  "), "顺手 strip，调用方不该为空白提心吊胆");

        assertFalse(p.has(null));
        assertNull(p.valueOf(null));
    }

    @Test
    @DisplayName("不可变：拿到 inferences 也改不动它")
    void isImmutable() {
        Persona p = current();
        assertThrows(UnsupportedOperationException.class,
                () -> p.inferences().add(i("crowd", "避开人多", "别去人挤人的地方")),
                "List.copyOf 之后谁也改不了内部状态 —— 不然合并的输入会在别处被悄悄改掉");

        List<Persona.Inference> source = new ArrayList<>();
        source.add(i("walking", "少走路", "我想有一个放松的旅行"));
        Persona copy = Persona.of(source);
        source.clear();
        assertEquals(1, copy.size(), "of() 要复制，不能挂着调用方的那个 List");
    }

    @Test
    @DisplayName("Inference 三处都 strip，且 null 归一成空串（下游只判空、不判 null）")
    void inferenceNormalises() {
        Persona.Inference raw = new Persona.Inference("  walking ", " 少走路 ", " 我想放松 ");

        assertEquals("walking", raw.key());
        assertEquals("少走路", raw.value());
        assertEquals("我想放松", raw.evidence());

        Persona.Inference nulls = new Persona.Inference(null, null, null);
        assertEquals("", nulls.key());
        assertEquals("", nulls.value());
        assertEquals("", nulls.evidence(), "三态在这里是多出来的复杂度，不是保护");
    }

    @Test
    @DisplayName("Inference 自己不做校验 —— 校验和记账都在闸门里")
    void inferenceDoesNotValidate() {
        // 这条记录一个**边界**：value 超长、key 不认识，Inference 都照收不误。
        // 如果在这里也拦一道，就会出现"两个闸门、只有一个在记账"的分叉 ——
        // 被记录对象丢掉的条目会无声消失，排障时你只会看到"模型没输出"。
        Persona.Inference weird = new Persona.Inference("不要早起", "一二三四五六七八九十一二三四五六七八", "嗯");
        assertEquals("不要早起", weird.key());
        assertFalse(weird.hasKnownKey(), "认不认识是**查**出来的，不是构造时拦下来的");
    }

    @Test
    @DisplayName("renderForPrompt：每条都带依据，中文名能查到时用中文名")
    void rendersForPrompt() {
        String line = current().renderForPrompt();

        assertTrue(line.contains("怕走路"), "用中文名，不是 key");
        assertTrue(line.contains("少走路"));
        assertTrue(line.contains("我想有一个放松的旅行"), "依据必须出现在 prompt 里 —— 模型才知道这是我们推的");
        assertTrue(line.contains("预算敏感"));
        assertEquals(2, line.lines().count(), "一行一条");

        // 认不出的 key 用 key 本身兜底显示，而不是整行消失：
        // 渲染的职责是"把已有的说出来"，不是"顺便再校验一遍"。
        String odd = Persona.of(List.of(i("不要早起", "晚点出门", "我不想早起"))).renderForPrompt();
        assertTrue(odd.contains("不要早起"), "本来就不该出现的 key，也应该**看得见**，而不是安静消失");
    }

    @Test
    @DisplayName("空 persona 渲染出空串（不是 null、也不是一堆空行）")
    void emptyRendersToNothing() {
        assertEquals("", Persona.empty().renderForPrompt());
    }
}
