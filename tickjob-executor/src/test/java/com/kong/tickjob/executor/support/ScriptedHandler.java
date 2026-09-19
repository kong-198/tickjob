package com.kong.tickjob.executor.support;

import com.kong.tickjob.executor.handler.IJobHandler;
import com.kong.tickjob.executor.handler.JobHandler;

/**
 * 测试夹具：一个名字固定、行为可替换的处理器。
 *
 * <p>注册表要求处理器必须标 {@link JobHandler}，没法直接塞匿名实现，
 * 所以这里用一个「壳」类占住名字，真正的行为由每个用例注入，避免为每种场景各写一个类。</p>
 *
 * <p>行为是静态字段：surefire 配置了 {@code parallel=none}，用例之间不会并发，
 * 每个用例在 {@code @BeforeEach} 里重置即可。</p>
 */
@JobHandler(ScriptedHandler.NAME)
public class ScriptedHandler implements IJobHandler {

    public static final String NAME = "scripted";

    /** 由用例注入；为 null 时表示用例忘了设置，应当明确报错而不是静默返回 */
    public static volatile IJobHandler body;

    public static void reset() {
        body = null;
    }

    public static void script(IJobHandler behavior) {
        body = behavior;
    }

    @Override
    public String execute(String param) throws Exception {
        IJobHandler current = body;
        if (current == null) {
            throw new IllegalStateException("测试未通过 ScriptedHandler.script(...) 设置处理器行为");
        }
        return current.execute(param);
    }
}
