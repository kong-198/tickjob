package com.kong.tickjob.common.cron;

import com.kong.tickjob.common.exception.TickJobException;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.function.IntUnaryOperator;

/**
 * 六域 Cron 表达式：{@code 秒 分 时 日 月 周}。
 *
 * <h3>为什么自己写</h3>
 * <p>Quartz 的 {@code CronExpression} 要拖进整个 quartz 依赖；Spring 的
 * {@code CronExpression} 是 {@code package-private} 且只支持 6 域里的注解场景，
 * 拿不到「给我算出下一次触发时间」这个能力。调度内核只需要两件事 ——
 * <b>校验表达式</b> 和 <b>算出下次触发时刻</b>，用位图实现不到 200 行，且完全可测。</p>
 *
 * <h3>实现要点</h3>
 * <ul>
 *   <li>每个域解析成一个 {@link BitSet}，「是否命中」退化成 O(1) 的位查询；</li>
 *   <li>求下次触发时间是「逐级进位」：月份不匹配就跳到下月 1 号零点，
 *       日不匹配就跳到次日零点…… 每一跳都顺手把低位域清零，
 *       所以平均只需要几十次循环，而不是逐秒穷举；</li>
 *   <li>支持 {@code * ? , - /} 与月份 / 星期英文别名（大小写不敏感）；</li>
 *   <li>星期域同时接受 {@code 0} 和 {@code 7} 表示周日 —— 这是使用者最容易踩的坑。</li>
 * </ul>
 *
 * <h3>日 / 周语义</h3>
 * <p>对齐 Quartz：两者必有一个为 {@code ?}（表示「不关心」）。若两者都写了具体值，
 * 则按 Unix cron 的 OR 语义处理（任一命中即触发）；两者同时为 {@code ?} 直接报错。</p>
 */
public final class CronExpression {

    /** 触发时间搜索上限：4 年。超过它仍未命中，说明这个表达式实际不可触发（如 2 月 30 日） */
    private static final int SEARCH_YEARS = 4;
    /** 循环保护上限，防止病态表达式把 CPU 占死 */
    private static final int MAX_ITERATIONS = 200_000;

    private static final String[] MONTH_ALIASES =
            {"JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"};

    /** 内部统一 0=周日 … 6=周六，与 {@code java.time.DayOfWeek#getValue() % 7} 对齐 */
    private static final String[] DAY_ALIASES =
            {"SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"};

    private final String expression;
    private final BitSet seconds;
    private final BitSet minutes;
    private final BitSet hours;
    private final BitSet daysOfMonth;
    private final BitSet months;
    private final BitSet daysOfWeek;
    private final boolean dayOfMonthUnspecified;
    private final boolean dayOfWeekUnspecified;

    private CronExpression(String expression, BitSet seconds, BitSet minutes, BitSet hours,
                           BitSet daysOfMonth, BitSet months, BitSet daysOfWeek,
                           boolean dayOfMonthUnspecified, boolean dayOfWeekUnspecified) {
        this.expression = expression;
        this.seconds = seconds;
        this.minutes = minutes;
        this.hours = hours;
        this.daysOfMonth = daysOfMonth;
        this.months = months;
        this.daysOfWeek = daysOfWeek;
        this.dayOfMonthUnspecified = dayOfMonthUnspecified;
        this.dayOfWeekUnspecified = dayOfWeekUnspecified;
    }

    // ------------------------------------------------------------------ 解析

    public static CronExpression parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw TickJobException.configError("Cron 表达式不能为空");
        }
        String normalized = expression.trim();
        String[] fields = normalized.split("\\s+");
        if (fields.length != 6) {
            throw TickJobException.configError(
                    "Cron 表达式必须是 6 个域「秒 分 时 日 月 周」，当前 %d 个：%s".formatted(fields.length, normalized));
        }

        boolean domUnspecified = "?".equals(fields[3]);
        boolean dowUnspecified = "?".equals(fields[5]);
        if (domUnspecified && dowUnspecified) {
            throw TickJobException.configError("「日」与「周」不能同时为 ?：" + normalized);
        }

        try {
            return new CronExpression(
                    normalized,
                    parseField(fields[0], "秒", 0, 59, null, IntUnaryOperator.identity()),
                    parseField(fields[1], "分", 0, 59, null, IntUnaryOperator.identity()),
                    parseField(fields[2], "时", 0, 23, null, IntUnaryOperator.identity()),
                    parseField(fields[3], "日", 1, 31, null, IntUnaryOperator.identity()),
                    parseField(fields[4], "月", 1, 12, MONTH_ALIASES, IntUnaryOperator.identity()),
                    parseField(fields[5], "周", 0, 7, DAY_ALIASES, v -> v == 7 ? 0 : v),
                    domUnspecified,
                    dowUnspecified);
        } catch (TickJobException e) {
            throw e;
        } catch (RuntimeException e) {
            throw TickJobException.configError("Cron 表达式解析失败 [" + normalized + "]：" + e.getMessage());
        }
    }

    public static boolean isValid(String expression) {
        try {
            parse(expression);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 解析单个域。
     *
     * <p>支持的写法：{@code *}、{@code ?}、{@code 5}、{@code 1-5}、{@code A-B}
     * （别名区间，如 {@code MON-FRI}）、{@code *&#47;10}、{@code 1-30&#47;5}、{@code 5&#47;10}
     * （从 5 开始每 10 个），以及用逗号组合的多段。</p>
     *
     * @param normalize 取值归一化（星期域用它把 7 折成 0）
     */
    private static BitSet parseField(String field, String fieldName, int min, int max,
                                     String[] aliases, IntUnaryOperator normalize) {
        if (field == null || field.isBlank()) {
            throw TickJobException.configError("「" + fieldName + "」域不能为空");
        }
        if ("?".equals(field)) {
            // ? 只允许出现在日 / 周，且语义等同 *（真正的含义由 dayOfMonthUnspecified 标记承载）
            if (!"日".equals(fieldName) && !"周".equals(fieldName)) {
                throw TickJobException.configError("「" + fieldName + "」域不支持 ?，只有「日」和「周」可以");
            }
            field = "*";
        }

        BitSet bits = new BitSet(max + 1);
        // limit = -1：保留尾部的空分段，让 "1,2," 这类笔误暴露出来而不是被静默吞掉
        for (String segment : field.split(",", -1)) {
            String part = segment.trim();
            if (part.isEmpty()) {
                throw TickJobException.configError("「" + fieldName + "」域存在空的逗号分段：" + field);
            }

            int step = 1;
            int slash = part.indexOf('/');
            if (slash >= 0) {
                String stepText = part.substring(slash + 1).trim();
                step = parseScalar(stepText, 1, Integer.MAX_VALUE, null, fieldName, "步长");
                part = part.substring(0, slash).trim();
            }

            int start;
            int end;
            if (part.isEmpty() || "*".equals(part)) {
                start = min;
                end = max;
            } else {
                int dash = part.indexOf('-', 1); // 从 1 开始找，跳过可能的前导负号
                if (dash > 0) {
                    start = parseScalar(part.substring(0, dash), min, max, aliases, fieldName, "起始值");
                    end = parseScalar(part.substring(dash + 1), min, max, aliases, fieldName, "结束值");
                } else {
                    start = parseScalar(part, min, max, aliases, fieldName, "取值");
                    // "5/10" 语义：从 5 开始，按步长一路取到域上限；单独一个 "5" 只取 5
                    end = slash >= 0 ? max : start;
                }
            }

            if (start > end) {
                throw TickJobException.configError(
                        "「%s」域区间起点大于终点：%d > %d（%s）".formatted(fieldName, start, end, field));
            }
            for (int value = start; value <= end; value += step) {
                bits.set(normalize.applyAsInt(value));
            }
        }

        if (bits.isEmpty()) {
            throw TickJobException.configError("「" + fieldName + "」域解析后没有任何有效取值：" + field);
        }
        return bits;
    }

    private static int parseScalar(String text, int min, int max, String[] aliases,
                                   String fieldName, String what) {
        String token = text.trim();
        if (token.isEmpty()) {
            throw TickJobException.configError("「" + fieldName + "」域的" + what + "为空");
        }
        if (aliases != null) {
            String upper = token.toUpperCase();
            for (int i = 0; i < aliases.length; i++) {
                if (aliases[i].equals(upper)) {
                    // 月份别名 1-based，星期别名 0-based
                    return MONTH_ALIASES == aliases ? i + 1 : i;
                }
            }
        }
        int value;
        try {
            value = Integer.parseInt(token);
        } catch (NumberFormatException e) {
            throw TickJobException.configError(
                    "「%s」域的%s不是合法数字或别名：%s".formatted(fieldName, what, token));
        }
        if (value < min || value > max) {
            throw TickJobException.configError(
                    "「%s」域的%s %d 超出范围 [%d, %d]".formatted(fieldName, what, value, min, max));
        }
        return value;
    }

    // ------------------------------------------------------------------ 求值

    /**
     * 求严格晚于 {@code from} 的下一次触发时刻（秒级精度，毫秒截断）。
     */
    public LocalDateTime next(LocalDateTime from) {
        LocalDateTime cursor = from.plusSeconds(1).withNano(0);
        LocalDateTime limit = from.plusYears(SEARCH_YEARS);

        int guard = 0;
        while (cursor.isBefore(limit)) {
            if (++guard > MAX_ITERATIONS) {
                break;
            }
            if (!months.get(cursor.getMonthValue())) {
                cursor = cursor.plusMonths(1).withDayOfMonth(1).with(LocalTime.MIDNIGHT);
                continue;
            }
            if (!dayMatches(cursor)) {
                cursor = cursor.plusDays(1).with(LocalTime.MIDNIGHT);
                continue;
            }
            if (!hours.get(cursor.getHour())) {
                cursor = cursor.plusHours(1).truncatedTo(ChronoUnit.HOURS);
                continue;
            }
            if (!minutes.get(cursor.getMinute())) {
                cursor = cursor.plusMinutes(1).truncatedTo(ChronoUnit.MINUTES);
                continue;
            }
            if (!seconds.get(cursor.getSecond())) {
                cursor = cursor.plusSeconds(1);
                continue;
            }
            return cursor;
        }
        throw TickJobException.configError(
                "表达式在未来 " + SEARCH_YEARS + " 年内没有找到触发时间，可能永远不会触发：" + expression);
    }

    /** 批量预览接下来 {@code count} 次触发时间，供任务编辑页做「接下来 5 次」提示 */
    public List<LocalDateTime> nextList(LocalDateTime from, int count) {
        List<LocalDateTime> result = new ArrayList<>(count);
        LocalDateTime cursor = from;
        for (int i = 0; i < count; i++) {
            cursor = next(cursor);
            result.add(cursor);
        }
        return result;
    }

    /** 该时刻是否命中表达式（用于测试与人工核对） */
    public boolean matches(LocalDateTime moment) {
        if (!months.get(moment.getMonthValue())
                || !hours.get(moment.getHour())
                || !minutes.get(moment.getMinute())
                || !seconds.get(moment.getSecond())) {
            return false;
        }
        return dayMatches(moment);
    }

    private boolean dayMatches(LocalDateTime moment) {
        if (dayOfMonthUnspecified) {
            return daysOfWeek.get(toSundayBased(moment));
        }
        if (dayOfWeekUnspecified) {
            return daysOfMonth.get(moment.getDayOfMonth());
        }
        // 两边都写了具体值：对齐 Unix cron，任一命中即触发
        return daysOfMonth.get(moment.getDayOfMonth()) || daysOfWeek.get(toSundayBased(moment));
    }

    /** {@code java.time} 的星期是 1=周一…7=周日，这里折成 0=周日…6=周六 */
    private static int toSundayBased(LocalDateTime moment) {
        return moment.getDayOfWeek().getValue() % 7;
    }

    public String getExpression() {
        return expression;
    }

    @Override
    public String toString() {
        return expression;
    }
}
