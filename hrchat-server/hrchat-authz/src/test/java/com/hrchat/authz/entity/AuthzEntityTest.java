package com.hrchat.authz.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实体模型 round-trip 单测：覆盖 Lombok 生成的访问器（S8a 覆盖率门禁）。
 */
class AuthzEntityTest {

    @Test
    void secUserRoundTrip() {
        SecUser e = new SecUser();
        e.setId(1L);
        e.setEmpNo("hr01");
        e.setDisplayName("张雨晴");
        e.setEmail("hr01@x.com");
        e.setOrgNodeId(2L);
        e.setStatus(1);
        e.setLastLoginAt(LocalDateTime.now());
        e.setPrefsJson("{}");
        e.setIsDeleted(0);
        e.setCreatedAt(LocalDateTime.now());
        e.setCreatedBy("sys");
        e.setUpdatedAt(LocalDateTime.now());
        e.setUpdatedBy("sys");
        assertThat(e.getEmpNo()).isEqualTo("hr01");
        assertThat(e.getDisplayName()).isEqualTo("张雨晴");
        assertThat(e.getStatus()).isEqualTo(1);
        assertThat(e.getOrgNodeId()).isEqualTo(2L);
    }

    @Test
    void secOrgNodeRoundTrip() {
        SecOrgNode e = new SecOrgNode();
        e.setId(2L);
        e.setOrgCode("RDC");
        e.setOrgName("研发中心");
        e.setParentId(1L);
        e.setOrgPath("/1/2/");
        e.setOrgLevel(2);
        e.setStatus(1);
        e.setHrEffectAt(LocalDateTime.now());
        e.setIsDeleted(0);
        e.setCreatedAt(LocalDateTime.now());
        e.setCreatedBy("sys");
        e.setUpdatedAt(LocalDateTime.now());
        e.setUpdatedBy("sys");
        assertThat(e.getOrgPath()).isEqualTo("/1/2/");
        assertThat(e.getOrgName()).isEqualTo("研发中心");
        assertThat(e.getOrgLevel()).isEqualTo(2);
    }

    @Test
    void secOrgGrantRoundTrip() {
        SecOrgGrant e = new SecOrgGrant();
        e.setId(1L);
        e.setGranteeType(1);
        e.setGranteeId("hr01");
        e.setOrgNodeId(2L);
        e.setGrantScope(2);
        e.setEffectiveAt(LocalDateTime.now().minusDays(1));
        e.setExpireAt(LocalDateTime.now().plusDays(1));
        e.setSourceType(1);
        assertThat(e.getGranteeId()).isEqualTo("hr01");
        assertThat(e.getOrgNodeId()).isEqualTo(2L);
        assertThat(e.getGrantScope()).isEqualTo(2);
        assertThat(e.getSourceType()).isEqualTo(1);
    }

    @Test
    void secRoleRoundTrip() {
        SecRole e = new SecRole();
        e.setId(1L);
        e.setRoleCode("HRBP");
        e.setRoleName("HRBP");
        e.setDataLevel(1);
        assertThat(e.getRoleCode()).isEqualTo("HRBP");
        assertThat(e.getDataLevel()).isEqualTo(1);
    }

    @Test
    void secUserRoleRoundTrip() {
        SecUserRole e = new SecUserRole();
        e.setId(1L);
        e.setUserId(1L);
        e.setRoleCode("HRBP");
        e.setGrantedBy("adm01");
        assertThat(e.getUserId()).isEqualTo(1L);
        assertThat(e.getRoleCode()).isEqualTo("HRBP");
    }

    @Test
    void secFieldPolicyRoundTrip() {
        SecFieldPolicy e = new SecFieldPolicy();
        e.setId(1L);
        e.setFieldCode("salary.gross_pay");
        e.setDomain("salary");
        e.setPolicyType(2);
        e.setRoleCode("HRBP");
        e.setMinGroupSize(5);
        e.setApprovalRequired(true);
        assertThat(e.getFieldCode()).isEqualTo("salary.gross_pay");
        assertThat(e.getPolicyType()).isEqualTo(2);
        assertThat(e.getApprovalRequired()).isTrue();
    }
}
