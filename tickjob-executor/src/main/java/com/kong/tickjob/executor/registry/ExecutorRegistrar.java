package com.kong.tickjob.executor.registry;

import com.fasterxml.jackson.core.type.TypeReference;
import com.kong.tickjob.common.protocol.RegistryParam;
import com.kong.tickjob.common.protocol.RpcResponse;
import com.kong.tickjob.common.protocol.TickJobApi;
import com.kong.tickjob.common.util.AddressUtils;
import com.kong.tickjob.common.util.HttpUtils;
import com.kong.tickjob.common.util.JsonUtils;
import com.kong.tickjob.executor.config.ExecutorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

/**
 * 执行器与调度中心之间的注册通道。
 *
 * <p>调度中心支持多实例部署，因此注册会<b>依次尝试所有配置的地址</b>，
 * 只要有一个成功就算注册成功。这样调度中心滚动发布期间执行器不会因为
 * 「正好连到那台正在重启的」而注册失败。</p>
 */
public class ExecutorRegistrar {

    private static final Logger log = LoggerFactory.getLogger(ExecutorRegistrar.class);

    private static final int REGISTER_TIMEOUT_SECONDS = 5;

    private final ExecutorProperties properties;
    private final String address;

    public ExecutorRegistrar(ExecutorProperties properties) {
        this.properties = properties;
        this.address = resolveAddress(properties);
    }

    /** 本执行器对外暴露的地址，注册与日志都用它 */
    public String address() {
        return address;
    }

    public boolean register() {
        if (properties.getAdminAddresses() == null || properties.getAdminAddresses().isEmpty()) {
            log.warn("未配置 tickjob.executor.admin-addresses，执行器不会注册到任何调度中心");
            return false;
        }
        RegistryParam param = new RegistryParam(properties.getAppName(), address);
        for (String admin : properties.getAdminAddresses()) {
            try {
                String response = HttpUtils.postJson(
                        TickJobApi.url(admin, TickJobApi.REGISTRY_REGISTER), param, REGISTER_TIMEOUT_SECONDS);
                RpcResponse<Void> parsed = JsonUtils.parse(response, new TypeReference<>() {
                });
                if (parsed.isOk()) {
                    log.info("执行器 [{}] 注册成功：{} -> {}", properties.getAppName(), address, admin);
                    return true;
                }
                log.warn("调度中心 {} 拒绝注册：{}", admin, parsed.msg());
            } catch (Exception e) {
                log.warn("向 {} 注册失败：{}", admin, e.getMessage());
            }
        }
        return false;
    }

    public void remove() {
        if (properties.getAdminAddresses() == null) {
            return;
        }
        RegistryParam param = new RegistryParam(properties.getAppName(), address);
        for (String admin : properties.getAdminAddresses()) {
            try {
                HttpUtils.postJson(TickJobApi.url(admin, TickJobApi.REGISTRY_REMOVE), param, 3);
                log.info("已从调度中心 {} 摘除执行器 {}", admin, address);
            } catch (Exception e) {
                // 摘除失败不影响进程退出：调度中心靠心跳超时也会把它剔除
                log.debug("从 {} 摘除失败（可忽略）：{}", admin, e.getMessage());
            }
        }
    }

    /** 执行结果回报给所有调度中心之一即可，这里复用注册地址列表 */
    public void report(String path, Object body) throws Exception {
        if (properties.getAdminAddresses() == null || properties.getAdminAddresses().isEmpty()) {
            return;
        }
        Exception last = null;
        for (String admin : properties.getAdminAddresses()) {
            try {
                HttpUtils.postJson(TickJobApi.url(admin, path), body, REGISTER_TIMEOUT_SECONDS);
                return;
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new IllegalStateException("没有可用的调度中心地址");
    }

    private static String resolveAddress(ExecutorProperties properties) {
        if (StringUtils.hasText(properties.getAddress())) {
            return properties.getAddress();
        }
        String ip = StringUtils.hasText(properties.getIp()) ? properties.getIp() : AddressUtils.findLocalIp();
        return AddressUtils.toHttpUrl(ip, properties.getPort());
    }
}
