package com.kong.tickjob.common.shard;

import com.kong.tickjob.common.shard.ShardingContext.ShardingVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("分片上下文")
class ShardingContextTest {

    @AfterEach
    void cleanUp() {
        // ThreadLocal 是静态的，用例之间必须清干净，否则会互相污染
        ShardingContext.unbind();
    }

    @Test
    @DisplayName("未绑定时是单分片语义：序号 0、总数 1、不广播")
    void defaultIsSingleShard() {
        assertEquals(0, ShardingContext.index());
        assertEquals(1, ShardingContext.total());
        assertFalse(ShardingContext.broadcast());
        assertEquals("0/1", ShardingContext.current().toString());
    }

    @Test
    @DisplayName("绑定后可读取分片序号与总数，总数大于 1 即为广播")
    void bindAndRead() {
        ShardingContext.bind(2, 5);
        assertEquals(2, ShardingContext.index());
        assertEquals(5, ShardingContext.total());
        assertTrue(ShardingContext.broadcast());
        assertEquals("2/5", ShardingContext.current().toString());
    }

    @Test
    @DisplayName("unbind 后回到默认值，避免线程池复用时分片信息串到下一个任务")
    void unbindRestoresDefault() {
        ShardingContext.bind(3, 8);
        assertEquals(3, ShardingContext.index());
        ShardingContext.unbind();
        assertEquals(0, ShardingContext.index());
        assertEquals(1, ShardingContext.total());
    }

    @Test
    @DisplayName("重新绑定直接覆盖旧值")
    void rebindOverwrites() {
        ShardingContext.bind(0, 3);
        ShardingContext.bind(2, 3);
        assertEquals(2, ShardingContext.index());
        assertEquals(3, ShardingContext.total());
    }

    @Test
    @DisplayName("分片序号越界或总数为 0 时立刻失败，不留下坏数据")
    void invalidShardRejected() {
        assertThrows(IllegalArgumentException.class, () -> ShardingContext.bind(5, 5));
        assertThrows(IllegalArgumentException.class, () -> ShardingContext.bind(-1, 5));
        assertThrows(IllegalArgumentException.class, () -> ShardingContext.bind(0, 0));
        assertThrows(IllegalArgumentException.class, () -> ShardingContext.bind(0, -2));
        // 失败后上下文未被改动
        assertEquals(0, ShardingContext.index());
    }

    @Test
    @DisplayName("分片信息绑在线程上，不会泄漏到其它线程")
    void isThreadIsolated() throws InterruptedException {
        ShardingContext.bind(0, 3);
        AtomicReference<ShardingVO> seenByOtherThread = new AtomicReference<>();

        Thread other = new Thread(() -> seenByOtherThread.set(ShardingContext.current()));
        other.start();
        other.join();

        assertEquals(1, seenByOtherThread.get().total(), "子线程应当看到默认的单分片");
        assertEquals(0, seenByOtherThread.get().index());
        assertEquals(3, ShardingContext.total(), "主线程自己的绑定不受影响");
    }
}
