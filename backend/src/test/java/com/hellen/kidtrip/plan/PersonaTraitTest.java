package com.hellen.kidtrip.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 维度闭集。它是"模型只能在想清楚的几件事上做推论"这条防线的全部实现。
 *
 * 要钉住 4 这个数字：多一个维度意味着多一类**我们没想清楚**的推论被放进来，
 * 而这类错误是静默的（一条错的偏好会一路影响排程，不报错）。
 * 加维度必须是一次显式的产品决定 —— 也就是，必须改这个测试。
 */
@DisplayName("persona 维度闭集：只有 4 个")
class PersonaTraitTest {

    @Test
    @DisplayName("正好 4 个，key 不重复、不空，标签和取值域都有")
    void has4WellFormedTraits() {
        assertEquals(4, PersonaTrait.size());

        List<String> keys = Arrays.stream(PersonaTrait.values()).map(PersonaTrait::key).toList();
        assertEquals(4, new HashSet<>(keys).size(), "key 有重复：" + keys);

        for (PersonaTrait t : PersonaTrait.values()) {
            assertFalse(t.key().isBlank(), "key 不能空：" + t);
            assertFalse(t.label().isBlank(), "中文标签不能空：" + t);
            assertFalse(t.domain().isBlank(), "取值域提示不能空：" + t);
        }
    }

    @Test
    @DisplayName("key 必须是英文小写 —— 中文 key 会挡不住同义写法")
    void keysAreLowercaseAscii() {
        // 这条钉的是一个设计选择，不是格式洁癖：
        // key 用中文的话，模型会写"怕走路 / 怕累 / 不爱走路"三种，
        // 三串都合法（闭集挡不住），但它们其实是同一条偏好 ——
        // 于是卡上出现三条意思一样的条目，同 key 覆盖的合并规则也失效。
        for (PersonaTrait t : PersonaTrait.values()) {
            assertTrue(t.key().matches("[a-z_]+"), "key 应该是小写字母/下划线：" + t.key());
        }
    }

    @Test
    @DisplayName("闭集里不该出现 TravelParams 已经装得下的维度")
    void doesNotDuplicateTheProfileFields() {
        // "节奏" 是 pace、"推车" 是 stroller、"想去哪类景点" 是 preferences。
        // 它们在画像里，persona 再存一遍就是**两处真相**，迟早打架 ——
        // 打架时没人知道该信哪一边。
        // ⚠️ `interest`（孩子的兴趣）曾经就是这样一个越界的维度：2026-10-15 实测后已移除 ——
        //    用户说"喜欢海洋馆，动物园"，画像记下两个、persona 只剩一个。
        //    把它钉进 banned 表里，防的是"哪天有人觉得 persona 该记兴趣"又把它加回来。
        // 所以这里按 key 的字面做一次粗筛：真出现同义词时，人来看这条测试就懂了。
        List<String> banned = List.of("pace", "stroller", "preferences", "interest", "days", "city", "origin", "date", "age");
        for (String b : banned) {
            for (PersonaTrait t : PersonaTrait.values()) {
                assertFalse(t.key().toLowerCase().contains(b),
                        "persona 不该再存一遍画像字段：" + t.key() + " 撞上了 " + b);
            }
        }
    }

    @Test
    @DisplayName("ofKey：认识 / 不认识 / 大小写 / 首尾空白")
    void resolvesKeys() {
        assertEquals(PersonaTrait.WALKING, PersonaTrait.ofKey("walking"));
        assertNull(PersonaTrait.ofKey("interest"), "兴趣归 preferences，不该是 persona 的维度了");

        assertEquals(PersonaTrait.WALKING, PersonaTrait.ofKey("  WALKING  "), "空白 + 大小写都要归一");
        assertEquals(PersonaTrait.CROWD, PersonaTrait.ofKey("Crowd"));

        assertNull(PersonaTrait.ofKey(null));
        assertNull(PersonaTrait.ofKey(""));
        assertNull(PersonaTrait.ofKey("   "));
        assertNull(PersonaTrait.ofKey("怕走路"), "中文标签不是 key —— 认它等于把同义写法放了进来");
        assertNull(PersonaTrait.ofKey("prefers_walking"), "模型最爱造这种'看起来很像'的 key");
        assertNull(PersonaTrait.ofKey("walking_speed"));
    }

    @Test
    @DisplayName("不认识时返回 null，绝不兜底成某个默认维度")
    void unknownKeyNeverFallsBack() {
        // 兜底一个默认值看起来更"健壮"，实际是**把没打算记的东西记成一条真的偏好**：
        // 用户的"不要早起"被兜底成 walking，从此卡片上写着"怕走路"，全程不报错。
        // 没有安全的默认值 —— 认不出就该整条丢。
        assertNull(PersonaTrait.ofKey("不要早起"));
        assertNull(PersonaTrait.ofKey("night_owl"));
    }

    @Test
    @DisplayName("isValidKey 和 ofKey 必须一致（它就是 ofKey != null 的写法糖）")
    void isValidKeyMatchesOfKey() {
        for (PersonaTrait t : PersonaTrait.values()) {
            assertTrue(PersonaTrait.isValidKey(t.key()));
            assertTrue(PersonaTrait.isValidKey(t.key().toUpperCase()));
        }
        assertFalse(PersonaTrait.isValidKey("怕走路"));
        assertFalse(PersonaTrait.isValidKey(null));
    }

    @Test
    @DisplayName("renderForPrompt：4 个维度都得出现，key 和中文名都要有")
    void rendersForPrompt() {
        String line = PersonaTrait.renderForPrompt();

        for (PersonaTrait t : PersonaTrait.values()) {
            assertTrue(line.contains(t.key()), "渲染里缺 key：" + t.key());
            assertTrue(line.contains(t.label()), "渲染里缺中文名：" + t.label());
        }
        // 只给 key 的话，模型得靠猜来理解 walking 在问什么；
        // 只给中文名的话，它没法原样回填一个我们认识的 key。
        assertTrue(line.lines().count() >= 4, "一行一个维度，逐行读才知道哪个是哪个");
    }

    @Test
    @DisplayName("每个维度都得说清它怎么改变排程 —— 这是'值不值得记'的判据")
    void everyTraitIsActionable() {
        // 这条不是格式检查，是**提醒**：以后想加维度时，
        // 先问"它会不会改变某一天排什么/住哪/怎么走"。
        // 答不上来的维度就是在收集没用的信息，还会挡掉 prompt 的空间。
        // 这里用一个最小粒度来兜：取值域里必须给出至少两个方向，
        // 只有一句话的维度（比如"好看"）说不清取什么值。
        for (PersonaTrait t : PersonaTrait.values()) {
            assertTrue(t.domain().contains("/") || t.domain().contains("如"),
                    "取值域得给出可选方向，否则模型只能自己编一个：" + t.key() + " → " + t.domain());
        }
        assertEquals(4, PersonaTrait.values().length, "加维度请同时更新这条断言和 PersonaTrait 的类注释");
    }

    @Test
    @DisplayName("没有第 5 个维度能被绕过 —— values() 就是全部")
    void closedSetIsExhaustive() {
        // enum 的价值就在这里：想加维度必须改这个文件，
        // 不可能出现"prompt 里教了 5 个、闸门只认 4 个"这种分叉。
        List<String> expected = List.of("walking", "crowd", "rest", "budget");
        List<String> actual = Arrays.stream(PersonaTrait.values()).map(PersonaTrait::key).collect(Collectors.toList());

        assertEquals(expected, actual, "维度顺序会被 renderForPrompt 展示给模型，改动要看得见");
    }

    @Test
    @DisplayName("每个维度都有自己的语义，没有两个指向同一件事")
    void traitsAreDistinct() {
        List<PersonaTrait> all = List.of(PersonaTrait.values());
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                assertFalse(all.get(i).label().equals(all.get(j).label()),
                        "两条标签一样，说明是同一个维度写了两次：" + all.get(i));
                assertFalse(all.get(i).domain().equals(all.get(j).domain()),
                        "两条取值域一样，说明这两个维度其实是同一件事：" + all.get(i) + " / " + all.get(j));
            }
        }
    }
}
