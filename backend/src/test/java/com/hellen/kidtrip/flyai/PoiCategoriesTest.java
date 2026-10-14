package com.hellen.kidtrip.flyai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 类别白名单。它是"模型不能自己造类别"这条防线的全部实现，所以值得单独钉。
 *
 * 特别要钉住 35 这个数字和"没有重复" —— 因为这份列表是从
 * `flyai search-poi --help` 的**中文枚举**抄下来的，而那份 help 本身是错的
 * （它印的是英文）。手抄错了不会有任何报错，只会让某个真实类别永远被当成非法值丢掉。
 */
@DisplayName("类别白名单：35 个闭集")
class PoiCategoriesTest {

    @Test
    @DisplayName("正好 35 个，且没有重复")
    void has35UniqueValues() {
        assertEquals(35, PoiCategories.size());
        assertEquals(35, new HashSet<>(PoiCategories.ALL).size(), "有重复项："
                + PoiCategories.ALL.stream()
                .filter(c -> PoiCategories.ALL.indexOf(c) != PoiCategories.ALL.lastIndexOf(c))
                .toList());
    }

    @Test
    @DisplayName("前端契约里那几个常用值必须都在 —— 它们是最容易漏抄的")
    void containsTheCommonOnes() {
        for (String c : List.of("动物园", "海洋馆", "主题乐园", "公园乐园", "博物馆", "水上乐园")) {
            assertTrue(PoiCategories.isValid(c), "缺了这个常用类别：" + c);
        }
    }

    @Test
    @DisplayName("认识的/不认识的：英文、自造词、景点名都不认")
    void validates() {
        assertTrue(PoiCategories.isValid("动物园"));
        assertFalse(PoiCategories.isValid("zoo"), "--help 里印的英文是错的，CLI 不认");
        assertFalse(PoiCategories.isValid("科技馆"), "模型最爱造的就是这种'差一点'的词");
        assertFalse(PoiCategories.isValid("珠海长隆海洋王国"), "景点名不是类别");
        assertFalse(PoiCategories.isValid(null));
        assertFalse(PoiCategories.isValid(""));
    }

    @Test
    @DisplayName("首尾空白会被 trim —— 一个空格不该把正确值判成非法")
    void trimsBeforeChecking() {
        // 不 trim 的后果不是"报错"，而是**一条看起来很像真的假告警**：
        // 用户会看到"期望的类别没找到"，而其实是我们把 " 动物园" 判成了非法。
        assertTrue(PoiCategories.isValid("  动物园  "));
    }

    @Test
    @DisplayName("keepValid：保序、丢弃非法、去重、受上限约束")
    void keepValidFilters() {
        List<String> raw = List.of("动物园", "科技馆", "海洋馆", "动物园", "主题乐园");

        List<String> kept = PoiCategories.keepValid(raw, 4);

        assertEquals(List.of("动物园", "海洋馆", "主题乐园"), kept,
                "顺序必须保持（它决定先补查哪一个），非法项消失，重复项只留一个");
    }

    @Test
    @DisplayName("keepValid：上限之外的部分被截掉")
    void keepValidRespectsMax() {
        List<String> raw = List.of("动物园", "海洋馆", "主题乐园", "公园乐园", "博物馆");

        assertEquals(List.of("动物园", "海洋馆"), PoiCategories.keepValid(raw, 2));
    }

    @Test
    @DisplayName("keepValid：null / 空 / 全是非法值 → 空列表（这是合法结果，代表'不补查'）")
    void keepValidDegeneratesToEmpty() {
        assertTrue(PoiCategories.keepValid(null, 4).isEmpty());
        assertTrue(PoiCategories.keepValid(List.of(), 4).isEmpty());
        assertTrue(PoiCategories.keepValid(Arrays.asList("迪士尼", null), 4).isEmpty());
    }

    @Test
    @DisplayName("renderForPrompt 里 35 个都得出现，用顿号分隔")
    void rendersForPrompt() {
        String line = PoiCategories.renderForPrompt();

        for (String c : PoiCategories.ALL) {
            assertTrue(line.contains(c), "渲染结果里缺：" + c);
        }
        assertTrue(line.contains("、"), "用顿号分隔，不是 JSON 数组 —— 这一行是要模型'读'的");
    }
}
