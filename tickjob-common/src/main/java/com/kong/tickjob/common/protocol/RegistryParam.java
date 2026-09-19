package com.kong.tickjob.common.protocol;

/**
 * 执行器注册 / 心跳参数。
 *
 * @param appName   执行器所属应用名，调度中心按它把地址分组
 * @param address   执行器 RPC 地址，形如 {@code http://10.0.0.7:9999}
 * @param weight    权重，用于按权重分流（当前路由策略未使用，预留给灰度）
 */
public record RegistryParam(String appName, String address, int weight) {

    public RegistryParam(String appName, String address) {
        this(appName, address, 1);
    }
}
