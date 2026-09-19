package com.kong.tickjob.common.protocol;

/**
 * 调度中心与执行器之间的接口路径常量。
 *
 * <p>这些字符串出现在两个模块里（执行器暴露、调度中心调用），抽成常量是为了
 * 避免「改了一边忘了另一边」造成的线上 404。这类错误在本地联调时很难发现，
 * 因为它只在跨进程调用时暴露。</p>
 */
public final class TickJobApi {

    // ---------------- 执行器侧（调度中心 → 执行器）----------------

    /** 触发一次任务执行 */
    public static final String RUN = "/run";
    /** 执行器心跳探测 */
    public static final String BEAT = "/beat";
    /** 空转检测：任务已下线时通知执行器清理 */
    public static final String IDLE_BEAT = "/idleBeat";

    // ---------------- 调度中心侧（执行器 → 调度中心）----------------

    /** 执行器注册 */
    public static final String REGISTRY_REGISTER = "/api/registry/register";
    /** 执行器摘除 */
    public static final String REGISTRY_REMOVE = "/api/registry/remove";
    /** 执行结果落库 */
    public static final String LOG_REPORT = "/api/log/report";

    private TickJobApi() {
    }

    public static String url(String baseUrl, String path) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + path;
    }
}
