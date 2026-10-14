package com.hellen.kidtrip.flyai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 城市名守门。
 *
 * 这些用例不是"随便想的边界"，前两条是**真实发生过的那一个值**：
 *   用户在界面上说"广东周边" → 模型照抄进 city → 整份行程排不出来。
 * 所以"广东周边"和"深圳周边"必须永远在拦下的那一侧 ——
 * 后一个还额外提醒了一件事：**它是我们自己写在 prompt 示例里的**（已改）。
 */
@DisplayName("城市名守门：区域说法 / 多城市 / 正常城市")
class CityNamesTest {

    @ParameterizedTest(name = "拦下「{0}」")
    @ValueSource(strings = {
            "广东周边",   // ⭐ 用户真实撞到的那个
            "深圳周边",   // ⭐ prompt 示例里写过的那个 —— 就是它把模型带偏的
            "珠海附近",
            "惠州一带",
            "粤东沿线",
            "广东省内",
            "珠三角",
            "大湾区",
            "粤港澳城市群",
            "广州、珠海",  // 多城市：一个 city 里塞两个城市
            "珠海/广州"
    })
    void rejectsAnythingThatIsNotOneCity(String raw) {
        assertFalse(CityNames.isSingleCity(raw), "这个词必须被拦住：" + raw);
    }

    @ParameterizedTest(name = "放过「{0}」")
    @ValueSource(strings = {"珠海", "广州", "深圳", "桂林", "三亚", "阳朔", "珠海市"})
    void acceptsOneConcreteCity(String raw) {
        assertTrue(CityNames.isSingleCity(raw), "这是正常城市名，不该被拦：" + raw);
    }

    @Test
    @DisplayName("首尾空白先 strip 再判 —— 否则「 珠海 」会被当成非法")
    void trimsBeforeChecking() {
        assertTrue(CityNames.isSingleCity("  珠海  "));
    }

    @Test
    @DisplayName("null / 空串 / 全空白 → 都不是城市（空值由上游那道校验负责报错，这里不抢）")
    void blanksAreNotCities() {
        assertFalse(CityNames.isSingleCity(null));
        assertFalse(CityNames.isSingleCity(""));
        assertFalse(CityNames.isSingleCity("   "));
    }

    @Test
    @DisplayName("拦住时给的话必须说清「怎么改」—— 只说「不合法」等于什么都没说")
    void hintTellsUserWhatToDo() {
        String hint = CityNames.hint();

        assertTrue(hint.contains("具体"), hint);
        assertTrue(hint.contains("珠海"), "必须给例子，否则用户不知道合法值长什么样：" + hint);
    }
}
