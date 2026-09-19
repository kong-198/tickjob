package com.kong.tickjob.common.util;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 轻量 HTTP 客户端，走 JDK 自带的 {@link HttpClient}，不引入任何额外依赖。
 *
 * <p>调度中心与执行器之间只需要「POST 一段 JSON、拿回一段 JSON」，
 * 为这点通信量引一个完整的 RPC 框架并不划算。选 JDK HttpClient 的另一个好处是
 * 执行器 SDK 可以完全不依赖 Web 容器 —— 业务方哪怕是个纯批处理应用也能接入。</p>
 */
public final class HttpUtils {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            // 执行器是内网短连接，不做重定向跟随，避免把请求打到意外的地方
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private HttpUtils() {
    }

    /**
     * POST JSON 并返回响应体。
     *
     * @throws ConnectException 对端不可达（调用方据此判定执行器失联并转路由）
     */
    public static String postJson(String url, Object body, int timeoutSeconds) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonUtils.toJson(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("对端返回 HTTP " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }
}
