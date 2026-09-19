package com.kong.tickjob.common.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

/**
 * 本机地址解析。
 *
 * <p>执行器注册时必须把自己「<b>能被调度中心访问到的</b>地址」报上去。直接用
 * {@code InetAddress.getLocalHost()} 在多网卡机器（装过 Docker、VPN 的开发机几乎都是）
 * 上经常返回 172.17.x.x 这类容器网段地址，调度中心拿着它根本连不上。</p>
 *
 * <p>这里的策略是：遍历网卡，取第一个「已启用 + 非回环 + 非虚拟」的站点本地 IPv4 地址；
 * 找不到就退回回环地址，同时留出 {@code tickjob.executor.ip} 配置项让使用者强制指定。</p>
 */
public final class AddressUtils {

    private static final Logger log = LoggerFactory.getLogger(AddressUtils.class);

    private AddressUtils() {
    }

    public static String findLocalIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nic = interfaces.nextElement();
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = nic.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (SocketException e) {
            log.warn("枚举网卡失败，退回回环地址：{}", e.getMessage());
        }
        return "127.0.0.1";
    }

    public static String toHttpUrl(String ip, int port) {
        return "http://" + ip + ":" + port;
    }
}
