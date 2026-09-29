package com.hrchat.authz.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 字段脱敏单测（BR-03：脱敏默认开启）。
 */
class DataMaskServiceTest {

    private final DataMaskService service = new DataMaskService();

    @Test
    void hidePolicyShouldReturnNull() {
        assertThat(service.mask("110101199001011234", "id_card", 1)).isNull();
    }

    @Test
    void maskIdCardKeepsHead2Tail4() {
        // 18 位身份证：保留前2后4，中间 12 位掩码
        assertThat(service.mask("110101199001011234", "id_card", 2)).isEqualTo("11************1234");
    }

    @Test
    void maskMobileKeepsHead3Tail4() {
        // 11 位手机号：保留前3后4，中间 4 位掩码
        assertThat(service.mask("13812345678", "mobile", 2)).isEqualTo("138****5678");
    }

    @Test
    void plainPolicyKeepsValue() {
        assertThat(service.mask("110101199001011234", "id_card", 4)).isEqualTo("110101199001011234");
    }

    @Test
    void summaryVisibleKeepsValue() {
        assertThat(service.mask("1234.56", "salary.gross_pay", 3)).isEqualTo("1234.56");
    }

    @Test
    void defaultMaskingForGenericField() {
        assertThat(service.mask("abcdefgh", "other", 2)).isEqualTo("ab****gh");
    }

    @Test
    void nullValuePassThrough() {
        assertThat(service.mask(null, "id_card", 2)).isNull();
    }

    @Test
    void shortValueFullyMasked() {
        assertThat(service.mask("123", "other", 2)).isEqualTo("****");
    }
}
