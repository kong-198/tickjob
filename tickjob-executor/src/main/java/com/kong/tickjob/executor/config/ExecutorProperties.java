package com.kong.tickjob.executor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 执行器配置，前缀 {@code tickjob.executor}。
 *
 * <pre>{@code
 * tickjob:
 *   executor:
 *     app-name: tickjob-demo
 *     admin-addresses: [ http://127.0.0.1:8080 ]
 *     port: 9999
 * }</pre>
 */
@ConfigurationProperties(prefix = "tickjob.executor")
public class ExecutorProperties {

    /** 是否启用执行器。默认开启；只想跑调度中心做纯管理时不启用即可 */
    private boolean enabled = true;

    /** 应用名。调度中心按它把执行器分组，任务配置里也要填这个值 */
    private String appName = "tickjob-executor";

    /** 调度中心地址，支持传多个（调度中心自身多实例部署时） */
    private List<String> adminAddresses = new ArrayList<>();

    /** 手动指定本机 IP。留空则自动探测第一个非回环 IPv4 地址 */
    private String ip;

    /** 执行器 RPC 端口 */
    private int port = 9999;

    /** 完整地址覆盖，形如 http://10.0.0.7:9999。填了它就不再拼 ip + port */
    private String address;

    /**
     * 通信令牌。非空时调度中心与执行器之间的请求都会带上它。
     * 注意：这是「内网互信」的最小防护，真正的安全边界应该由网络隔离和网关承担。
     */
    private String accessToken;

    /** 心跳间隔（秒）。调度中心据此判定执行器存活（约定 3 倍作为失联阈值） */
    private int registryIntervalSeconds = 30;

    /** 业务处理线程上限。超过后 handler 会排队，触发日志里能看到耗时变长 */
    private int maxHandlerThreads = 200;

    /** 空闲任务线程的保活时长（秒）：超过这个时间没任务可跑就回收线程 */
    private int idleThreadKeepAliveSeconds = 60;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public List<String> getAdminAddresses() {
        return adminAddresses;
    }

    public void setAdminAddresses(List<String> adminAddresses) {
        this.adminAddresses = adminAddresses;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public int getRegistryIntervalSeconds() {
        return registryIntervalSeconds;
    }

    public void setRegistryIntervalSeconds(int registryIntervalSeconds) {
        this.registryIntervalSeconds = registryIntervalSeconds;
    }

    public int getMaxHandlerThreads() {
        return maxHandlerThreads;
    }

    public void setMaxHandlerThreads(int maxHandlerThreads) {
        this.maxHandlerThreads = maxHandlerThreads;
    }

    public int getIdleThreadKeepAliveSeconds() {
        return idleThreadKeepAliveSeconds;
    }

    public void setIdleThreadKeepAliveSeconds(int idleThreadKeepAliveSeconds) {
        this.idleThreadKeepAliveSeconds = idleThreadKeepAliveSeconds;
    }
}
