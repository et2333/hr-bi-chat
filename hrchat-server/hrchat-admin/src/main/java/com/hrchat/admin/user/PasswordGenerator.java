package com.hrchat.admin.user;

import java.security.SecureRandom;

/**
 * 随机密码生成器（本地演示登录用，SSO 对接后废弃，当前仅演示）。
 *
 * <p>字母数字混排、去易混淆字符（0/O/1/I/l），SecureRandom 生成。</p>
 */
public final class PasswordGenerator {

    private static final char[] CHARS =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789".toCharArray();

    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordGenerator() {
    }

    /**
     * 生成指定长度随机密码。
     *
     * @param length 长度（至少 8）
     * @return 随机密码
     */
    public static String generate(int length) {
        int len = Math.max(8, length);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(CHARS[RANDOM.nextInt(CHARS.length)]);
        }
        return sb.toString();
    }

    /** 生成 12 位随机密码（用户创建/重置默认长度）。 */
    public static String generate() {
        return generate(12);
    }
}
