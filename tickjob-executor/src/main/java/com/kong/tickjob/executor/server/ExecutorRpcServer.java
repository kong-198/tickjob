package com.kong.tickjob.executor.server;

import com.kong.tickjob.common.protocol.IdleBeatParam;
import com.kong.tickjob.common.protocol.RegistryParam;
import com.kong.tickjob.common.protocol.RpcResponse;
import com.kong.tickjob.common.protocol.TickJobApi;
import com.kong.tickjob.common.protocol.TriggerParam;
import com.kong.tickjob.common.util.JsonUtils;
import com.kong.tickjob.executor.config.ExecutorProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 执行器 RPC 服务端，基于 JDK 自带的 {@code com.sun.net.httpserver}。
 *
 * <h3>为什么不用 Spring MVC</h3>
 * <p>执行器是以 SDK 形式被业务方引入的。如果依赖 MVC，就等于要求接入方<b>必须是个 Web 应用</b>；
 * 而定时任务的宿主很可能是纯批处理进程、甚至是个命令行工具。用 JDK 内置的 HttpServer
 * 可以完全自主控制端口与线程，也避免和业务应用抢 Web 容器的线程池。</p>
 *
 * <p>代价是缺少过滤器、序列化、异常处理这些便利设施，所以这里把手写的
 * 「路由 + JSON 编解码 + 错误兜底」集中在 {@link #dispatch} 一个方法里，方便通读。</p>
 */
public class ExecutorRpcServer {

    private static final Logger log = LoggerFactory.getLogger(ExecutorRpcServer.class);

    private final ExecutorProperties properties;
    private final ExecutorRpcService rpcService;

    private HttpServer server;
    private ExecutorService httpExecutor;

    public ExecutorRpcServer(ExecutorProperties properties, ExecutorRpcService rpcService) {
        this.properties = properties;
        this.rpcService = rpcService;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(properties.getPort()), 0);
        // 所有路径都走同一个分发方法：路径少（3 个），用一个 switch 比注册多个 context 更好读
        server.createContext("/", this::dispatch);
        httpExecutor = Executors.newFixedThreadPool(16, namedFactory());
        server.setExecutor(httpExecutor);
        server.start();
        log.info("执行器 RPC 服务已启动，监听端口 {}", properties.getPort());
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
            log.info("执行器 RPC 服务已停止");
        }
        if (httpExecutor != null) {
            httpExecutor.shutdownNow();
        }
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeError(exchange, 405, "只接受 POST");
                return;
            }
            if (!checkToken(exchange)) {
                writeError(exchange, 401, "accessToken 校验失败");
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            switch (path) {
                case TickJobApi.RUN ->
                        write(exchange, 200, rpcService.run(JsonUtils.parse(body, TriggerParam.class)));
                case TickJobApi.BEAT ->
                        write(exchange, 200, rpcService.beat(JsonUtils.parse(body, RegistryParam.class)));
                case TickJobApi.IDLE_BEAT ->
                        write(exchange, 200, rpcService.idleBeat(JsonUtils.parse(body, IdleBeatParam.class)));
                default -> writeError(exchange, 404, "未知路径：" + path);
            }
        } catch (Exception e) {
            log.error("处理执行器请求失败，path={}", path, e);
            writeError(exchange, 500, e.getMessage());
        } finally {
            exchange.close();
        }
    }

    /** 错误响应：响应体里的 code 与 HTTP 状态码保持同一个值，不给调用方留两套码去对照 */
    private void writeError(HttpExchange exchange, int httpStatus, String message) throws IOException {
        write(exchange, httpStatus, RpcResponse.fail(httpStatus, message));
    }

    private boolean checkToken(HttpExchange exchange) {
        String expected = properties.getAccessToken();
        if (expected == null || expected.isBlank()) {
            return true;
        }
        String actual = exchange.getRequestHeaders().getFirst("TickJob-Token");
        return expected.equals(actual);
    }

    private void write(HttpExchange exchange, int httpStatus, Object body) throws IOException {
        byte[] payload = JsonUtils.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(httpStatus, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private static ThreadFactory namedFactory() {
        AtomicInteger seq = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "tickjob-executor-rpc-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
