package com.kong.tickjob.common.enums;

/**
 * 调度阻塞处理策略 —— 当同一个任务的<b>上一次执行还没结束</b>，新一次触发到来时怎么办。
 *
 * <p>这个选择本质上是在「不丢任务」和「不堆积」之间取舍：</p>
 * <ul>
 *   <li>多数定时任务（对账、报表）用 {@link #SERIAL}，宁可排队也不能漏；</li>
 *   <li>状态同步类任务用 {@link #COVER_EARLY}，只要最新状态；</li>
 *   <li>监控上报类任务用 {@link #DISCARD_LATER}，过期数据没有价值。</li>
 * </ul>
 */
public enum BlockStrategy {

    /** 串行等待：本次触发进入队列，等上一次执行完再执行（默认） */
    SERIAL_EXECUTION,

    /** 丢弃后续：若该任务正在执行，本次触发直接丢弃并记录到日志 */
    DISCARD_LATER,

    /** 覆盖之前：中断正在执行的那一次，立即执行最新一次 */
    COVER_EARLY
}
