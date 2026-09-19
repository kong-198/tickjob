package com.kong.tickjob.common.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * JSON 工具。持有一个配置好的 {@link ObjectMapper} 单例。
 *
 * <p>{@code ObjectMapper} 构造代价高但线程安全，因此全局复用一个实例；
 * 另外关闭 {@code FAIL_ON_UNKNOWN_PROPERTIES}：调度中心与执行器版本不可能永远同步，
 * 对端多下发一个字段不应该导致这次触发直接失败。</p>
 */
public final class JsonUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private JsonUtils() {
    }

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("序列化失败: " + e.getMessage(), e);
        }
    }

    public static <T> T parse(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("反序列化失败: " + e.getMessage(), e);
        }
    }

    public static <T> T parse(String json, TypeReference<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("反序列化失败: " + e.getMessage(), e);
        }
    }
}
