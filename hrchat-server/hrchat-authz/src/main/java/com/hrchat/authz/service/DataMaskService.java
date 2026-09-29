package com.hrchat.authz.service;

import org.springframework.stereotype.Service;

/**
 * 字段级脱敏服务（BR-03：脱敏默认开启）。
 *
 * <p>策略：1隐藏（返回 null）、2脱敏（中间段掩码）、3汇总可见（原样）、4明文（原样，需审批）。</p>
 */
@Service
public class DataMaskService {

    /**
     * 按策略处理字段值。
     *
     * @param value 原始值
     * @param fieldCode 字段标识（决定掩码形态）
     * @param policyType 策略类型
     * @return 展示值
     */
    public String mask(String value, String fieldCode, int policyType) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return switch (policyType) {
            case 1 -> null;          // 隐藏
            case 3, 4 -> value;      // 汇总可见 / 明文
            default -> maskSensitive(value, fieldCode);
        };
    }

    /** 按字段形态掩码：id_card 保留前2后4；mobile 保留前3后4；其余保留首尾各25%。 */
    private String maskSensitive(String value, String fieldCode) {
        if ("id_card".equals(fieldCode)) {
            return keepHeadTail(value, 2, 4);
        }
        if ("mobile".equals(fieldCode)) {
            return keepHeadTail(value, 3, 4);
        }
        if (value.length() <= 4) {
            return "****";
        }
        int head = Math.max(1, value.length() / 4);
        return keepHeadTail(value, head, head);
    }

    /** 保留首尾字符，中间以 * 填充。 */
    private String keepHeadTail(String value, int headLen, int tailLen) {
        if (value.length() <= headLen + tailLen) {
            return value;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(value, 0, headLen);
        sb.append("*".repeat(value.length() - headLen - tailLen));
        sb.append(value, value.length() - tailLen, value.length());
        return sb.toString();
    }
}
