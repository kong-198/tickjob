package com.kong.tickjob.executor.handler;

import com.kong.tickjob.common.exception.TickJobException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("任务处理器注册表")
class JobHandlerRegistryTest {

    @JobHandler("echo")
    static class EchoHandler implements IJobHandler {
        @Override
        public String execute(String param) {
            return param;
        }
    }

    /** 与 EchoHandler 同名的另一个类，用来验证重名检测 */
    @JobHandler("echo")
    static class AnotherEchoHandler implements IJobHandler {
        @Override
        public String execute(String param) {
            return param;
        }
    }

    @JobHandler("zzz")
    static class LastAlphabeticallyHandler implements IJobHandler {
        @Override
        public String execute(String param) {
            return null;
        }
    }

    @JobHandler("")
    static class BlankNameHandler implements IJobHandler {
        @Override
        public String execute(String param) {
            return null;
        }
    }

    static class UnannotatedHandler implements IJobHandler {
        @Override
        public String execute(String param) {
            return null;
        }
    }

    /** 注解标在父类上、注册的是子类 —— 对应 Spring 生成代理子类的情况 */
    static class SubclassOfEcho extends EchoHandler {
    }

    private static Map<String, IJobHandler> beans(String name1, IJobHandler h1) {
        Map<String, IJobHandler> map = new LinkedHashMap<>();
        map.put(name1, h1);
        return map;
    }

    private static Map<String, IJobHandler> beans(String name1, IJobHandler h1, String name2, IJobHandler h2) {
        Map<String, IJobHandler> map = beans(name1, h1);
        map.put(name2, h2);
        return map;
    }

    @Test
    @DisplayName("按注解声明的名字建索引")
    void registersByAnnotationValue() {
        JobHandlerRegistry registry = new JobHandlerRegistry(beans("echoHandler", new EchoHandler()));
        assertEquals(1, registry.size());
        assertTrue(registry.contains("echo"));
        assertFalse(registry.contains("echoHandler"), "索引键应当是注解里的名字，而不是 bean 名");
        assertSame(EchoHandler.class, registry.require("echo").getClass());
    }

    @Test
    @DisplayName("未标注解的实现被忽略，而不是报错 —— 业务方可能只是想复用接口")
    void ignoresUnannotatedHandlers() {
        JobHandlerRegistry registry = new JobHandlerRegistry(beans("plain", new UnannotatedHandler()));
        assertEquals(0, registry.size());
        assertTrue(registry.names().isEmpty());
    }

    @Test
    @DisplayName("注解标在父类上时也能识别（代理子类的场景）")
    void findsAnnotationOnSuperclass() {
        JobHandlerRegistry registry = new JobHandlerRegistry(beans("proxied", new SubclassOfEcho()));
        assertTrue(registry.contains("echo"));
        assertSame(SubclassOfEcho.class, registry.require("echo").getClass());
    }

    @Test
    @DisplayName("重名在启动期就抛错，而不是等任务被调度到才发现")
    void rejectsDuplicateNames() {
        TickJobException ex = assertThrows(TickJobException.class,
                () -> new JobHandlerRegistry(beans("a", new EchoHandler(), "b", new AnotherEchoHandler())));
        assertTrue(ex.getMessage().contains("echo"), "错误信息里应当带上冲突的名字：" + ex.getMessage());
    }

    @Test
    @DisplayName("注解名字为空时拒绝启动")
    void rejectsBlankName() {
        assertThrows(TickJobException.class,
                () -> new JobHandlerRegistry(beans("blank", new BlankNameHandler())));
    }

    @Test
    @DisplayName("取不存在的处理器时，错误信息里列出已注册的名字便于排查")
    void requireMissingHandlerExplainsAvailableNames() {
        JobHandlerRegistry registry = new JobHandlerRegistry(beans("echo", new EchoHandler()));
        TickJobException ex = assertThrows(TickJobException.class, () -> registry.require("notExists"));
        assertTrue(ex.getMessage().contains("notExists"));
        assertTrue(ex.getMessage().contains("echo"), "应当把可用的名字列出来：" + ex.getMessage());
    }

    @Test
    @DisplayName("名字列表有序且不可被外部修改")
    void namesAreSortedAndImmutable() {
        JobHandlerRegistry registry = new JobHandlerRegistry(
                beans("zzzBean", new LastAlphabeticallyHandler(), "echo", new EchoHandler()));
        assertEquals(2, registry.size());
        assertEquals(List.of("echo", "zzz"), List.copyOf(registry.names()));
        assertThrows(UnsupportedOperationException.class, () -> registry.names().add("hack"));
    }

    @Test
    @DisplayName("传入 null 或空集合时构造器可用，只是没有任何处理器")
    void toleratesEmptyInput() {
        assertEquals(0, new JobHandlerRegistry(null).size());
        assertEquals(0, new JobHandlerRegistry(Map.of()).size());
    }
}
