package com.kong.tickjob.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 一致性哈希用的散列工具。
 *
 * <p>采用 Ketama 风格：对键做 MD5，取摘要的<b>低 4 字节</b>拼成一个无符号 32 位数。
 * 相比 {@code String.hashCode()}，MD5 的雪崩效应更好，虚拟节点在环上的分布更均匀，
 * 否则「同一个任务永远落到同一台」这句话在节点数变化时会严重失衡。</p>
 */
public final class HashUtils {

    private HashUtils() {
    }

    public static long md5Hash(String key) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] digest = md5.digest(key.getBytes(StandardCharsets.UTF_8));
            return ((long) (digest[3] & 0xFF) << 24)
                    | ((long) (digest[2] & 0xFF) << 16)
                    | ((long) (digest[1] & 0xFF) << 8)
                    | (digest[0] & 0xFFL);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 未提供 MD5 实现", e);
        }
    }
}
