package com.kong.tickjob.common.cron;

import com.kong.tickjob.common.exception.TickJobException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Cron 表达式解析与下次触发时间计算")
class CronExpressionTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 3, 10, 9, 0, 0);

    // ------------------------------------------------------------------ 基本语义

    @Test
    @DisplayName("每天中午 12:00 触发")
    void dailyNoon() {
        CronExpression cron = CronExpression.parse("0 0 12 * * ?");
        assertEquals(LocalDateTime.of(2026, 3, 10, 12, 0, 0), cron.next(BASE));
    }

    @Test
    @DisplayName("next 是严格大于：刚好命中该时刻时返回下一次")
    void nextIsStrictlyAfter() {
        CronExpression cron = CronExpression.parse("0 0 12 * * ?");
        LocalDateTime exactHit = LocalDateTime.of(2026, 3, 10, 12, 0, 0);
        assertEquals(LocalDateTime.of(2026, 3, 11, 12, 0, 0), cron.next(exactHit));
    }

    @Test
    @DisplayName("工作日 10:15 触发 —— 周五之后跳到下周一")
    void weekdays() {
        CronExpression cron = CronExpression.parse("0 15 10 ? * MON-FRI");
        // 2026-03-13 是周五
        assertEquals(LocalDateTime.of(2026, 3, 13, 10, 15), cron.next(LocalDateTime.of(2026, 3, 13, 9, 0)));
        // 周六、周日都不触发，直接落到 3-16 周一
        assertEquals(LocalDateTime.of(2026, 3, 16, 10, 15), cron.next(LocalDateTime.of(2026, 3, 13, 11, 0)));
    }

    @Test
    @DisplayName("步长：14 点内每 5 分钟一次")
    void stepWithinHour() {
        CronExpression cron = CronExpression.parse("0 0/5 14 * * ?");
        assertEquals(LocalDateTime.of(2026, 3, 10, 14, 0), cron.next(LocalDateTime.of(2026, 3, 10, 13, 59)));
        assertEquals(LocalDateTime.of(2026, 3, 10, 14, 55), cron.next(LocalDateTime.of(2026, 3, 10, 14, 52)));
        // 14:55 之后当天不再触发，跳到次日 14:00
        assertEquals(LocalDateTime.of(2026, 3, 11, 14, 0), cron.next(LocalDateTime.of(2026, 3, 10, 14, 56)));
    }

    @Test
    @DisplayName("秒级任务：每 5 秒一次")
    void everyFiveSeconds() {
        CronExpression cron = CronExpression.parse("0/5 * * * * ?");
        assertEquals(LocalDateTime.of(2026, 3, 10, 9, 0, 5), cron.next(LocalDateTime.of(2026, 3, 10, 9, 0, 0)));
        assertEquals(LocalDateTime.of(2026, 3, 10, 9, 0, 10), cron.next(LocalDateTime.of(2026, 3, 10, 9, 0, 5)));
    }

    @Test
    @DisplayName("范围取值：每小时的 0-10 分整点秒")
    void rangeValues() {
        CronExpression cron = CronExpression.parse("0 0-10 * * * ?");
        assertTrue(cron.matches(LocalDateTime.of(2026, 3, 10, 9, 7, 0)));
        assertFalse(cron.matches(LocalDateTime.of(2026, 3, 10, 9, 11, 0)));
    }

    @Test
    @DisplayName("按值起步的步长：秒 = 5/10 表示 5,15,25,35,45,55")
    void startWithStep() {
        CronExpression cron = CronExpression.parse("5/10 * * * * ?");
        assertTrue(cron.matches(LocalDateTime.of(2026, 3, 10, 9, 0, 5)));
        assertTrue(cron.matches(LocalDateTime.of(2026, 3, 10, 9, 0, 15)));
        assertFalse(cron.matches(LocalDateTime.of(2026, 3, 10, 9, 0, 0)));
        assertFalse(cron.matches(LocalDateTime.of(2026, 3, 10, 9, 0, 10)));
    }

    // ------------------------------------------------------------------ 日期边界

    @Test
    @DisplayName("闰年 2 月 29 日：2026 年之后下一次是 2028 年")
    void leapDay() {
        CronExpression cron = CronExpression.parse("0 0 0 29 2 ?");
        assertEquals(LocalDateTime.of(2028, 2, 29, 0, 0), cron.next(LocalDateTime.of(2026, 3, 10, 0, 0)));
    }

    @Test
    @DisplayName("跨月：1 月 31 日的下个月落在 2 月 1 日而不是 2 月 31 日")
    void monthRollover() {
        CronExpression cron = CronExpression.parse("0 0 0 1 * ?");
        assertEquals(LocalDateTime.of(2026, 4, 1, 0, 0), cron.next(LocalDateTime.of(2026, 3, 10, 0, 0)));
    }

    @Test
    @DisplayName("年份翻篇：12 月 31 日之后进入次年 1 月 1 日")
    void yearRollover() {
        CronExpression cron = CronExpression.parse("0 0 0 1 1 ?");
        assertEquals(LocalDateTime.of(2027, 1, 1, 0, 0), cron.next(LocalDateTime.of(2026, 3, 10, 0, 0)));
    }

    @Test
    @DisplayName("永远不触发的表达式应当抛错而不是死循环")
    void impossibleExpression() {
        // 2 月没有 30 日
        CronExpression cron = CronExpression.parse("0 0 0 30 2 ?");
        assertThrows(TickJobException.class, () -> cron.next(BASE));
    }

    // ------------------------------------------------------------------ 星期取值

    @ParameterizedTest(name = "周日可以写成 {0}")
    @ValueSource(strings = {"0", "7", "SUN", "sun"})
    @DisplayName("周日既接受 0 也接受 7，别名大小写不敏感")
    void sundayVariants(String dayValue) {
        CronExpression cron = CronExpression.parse("0 0 9 ? * " + dayValue);
        // 2026-03-15 是周日
        assertEquals(LocalDateTime.of(2026, 3, 15, 9, 0), cron.next(LocalDateTime.of(2026, 3, 10, 0, 0)));
    }

    @Test
    @DisplayName("月份别名：JAN / dec 都认")
    void monthAliases() {
        assertEquals(LocalDateTime.of(2027, 1, 1, 0, 0),
                CronExpression.parse("0 0 0 1 JAN ?").next(LocalDateTime.of(2026, 3, 10, 0, 0)));
        assertEquals(LocalDateTime.of(2026, 12, 1, 0, 0),
                CronExpression.parse("0 0 0 1 dec ?").next(LocalDateTime.of(2026, 3, 10, 0, 0)));
    }

    @Test
    @DisplayName("日与周同时指定时按 OR 语义（Unix cron 习惯）")
    void dayOrWeekSemantics() {
        // 每月 15 号 或者 每周一
        CronExpression cron = CronExpression.parse("0 0 8 15 * MON");
        assertTrue(cron.matches(LocalDateTime.of(2026, 3, 15, 8, 0)), "每月 15 号应触发");
        // 2026-03-16 是周一
        assertTrue(cron.matches(LocalDateTime.of(2026, 3, 16, 8, 0)), "每周一应触发");
        assertFalse(cron.matches(LocalDateTime.of(2026, 3, 17, 8, 0)), "既不是 15 号也不是周一");
    }

    // ------------------------------------------------------------------ 参数校验

    @ParameterizedTest(name = "非法表达式 [{0}] 应当被拒绝")
    @ValueSource(strings = {
            "0 0 12 * *",              // 只有 5 个域
            "0 0 12 * * ? *",          // 7 个域
            "0 0 12 ? * ?",            // 日与周同时为 ?
            "0 0 25 * * ?",            // 小时超出 0-23
            "0 60 * * * ?",            // 分钟超出 0-59
            "0 0 12 32 * ?",           // 日超出 1-31
            "0 0 12 * 13 ?",           // 月超出 1-12
            "0 0 12 ? * 8",            // 周超出 0-7
            "0 0 12 * * FOO",          // 非法别名
            "0 0 12 *, * ?",           // 空的分段
            "0 0 12 5-1 * ?",          // 区间起止颠倒
            "*/0 0 12 * * ?",          // 步长为 0
            "0 0 12 * * ? x"           // 尾随多余内容
    })
    void invalidExpressions(String expression) {
        assertThrows(RuntimeException.class, () -> CronExpression.parse(expression),
                "应当拒绝：" + expression);
    }

    @Test
    @DisplayName("? 只能出现在日 / 周两个域")
    void questionMarkOnlyForDayFields() {
        assertThrows(TickJobException.class, () -> CronExpression.parse("? 0 12 * * ?"));
        assertThrows(TickJobException.class, () -> CronExpression.parse("0 0 ? * * ?"));
    }

    @Test
    @DisplayName("非法表达式抛的是配置异常，且错误信息里带上域名称")
    void errorMessageIsHelpful() {
        TickJobException ex = assertThrows(TickJobException.class,
                () -> CronExpression.parse("0 0 25 * * ?"));
        assertTrue(ex.getMessage().contains("时"), "错误信息应指明是哪个域出错，实际：" + ex.getMessage());
    }

    @ParameterizedTest(name = "合法性判定 [{0}] -> {1}")
    @CsvSource(delimiter = '|', value = {
            "0 0 12 * * ? | true",
            "0 0/5 * * * ? | true",
            "0 0 12 * * | false",
            "0 0 12 ? * ? | false",
            "0 0 25 * * ? | false"
    })
    void isValid(String expression, boolean expected) {
        assertEquals(expected, CronExpression.isValid(expression));
    }

    @Test
    @DisplayName("空表达式与 null 都判为非法，不抛异常")
    void blankExpressionIsInvalid() {
        assertFalse(CronExpression.isValid(null));
        assertFalse(CronExpression.isValid(""));
        assertFalse(CronExpression.isValid("   "));
    }

    // ------------------------------------------------------------------ 批量预览

    @Test
    @DisplayName("预览接下来 N 次触发时间，严格递增")
    void previewList() {
        CronExpression cron = CronExpression.parse("0 0 12 * * ?");
        List<LocalDateTime> next = cron.nextList(BASE, 3);
        assertEquals(List.of(
                LocalDateTime.of(2026, 3, 10, 12, 0),
                LocalDateTime.of(2026, 3, 11, 12, 0),
                LocalDateTime.of(2026, 3, 12, 12, 0)), next);
    }
}
