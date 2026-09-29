package com.hrchat.model.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisMapperBuilderAssistant;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.audit.model.AuditEvents;
import com.hrchat.audit.service.AuditCollector;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.tenant.TenantContextHolder;
import com.hrchat.common.exception.BizException;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.model.dto.LlmViews;
import com.hrchat.model.entity.LlmDeployState;
import com.hrchat.model.entity.LlmModelConfig;
import com.hrchat.model.entity.LlmModelVersion;
import com.hrchat.model.mapper.LlmDeployStateMapper;
import com.hrchat.model.mapper.LlmModelConfigMapper;
import com.hrchat.model.mapper.LlmModelVersionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LLM 配置管理单测：创建/重复校验/列表/详情脱敏/更新新版本/删除/版本列表。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LlmConfigServiceTest {

    @Mock
    private LlmModelConfigMapper configMapper;
    @Mock
    private LlmModelVersionMapper versionMapper;
    @Mock
    private LlmDeployStateMapper deployStateMapper;
    @Mock
    private AuditCollector auditCollector;

    private LlmConfigService service;

    @BeforeEach
    void setUp() {
        // 纯单测环境下初始化 TableInfo 缓存，使 LambdaQueryWrapper 可渲染 SQL 片段
        TableInfoHelper.initTableInfo(new MybatisMapperBuilderAssistant(new MybatisConfiguration(), ""),
                LlmModelConfig.class);
        service = new LlmConfigService(configMapper, versionMapper, deployStateMapper, auditCollector,
                new ObjectMapper());
    }

    private UserContext ctx() {
        return UserContext.builder().empNo("hr01").build();
    }

    @Test
    void create_ok_writesVersionAndDeployState() {
        when(configMapper.selectCount(any())).thenReturn(0L);
        doAnswer(inv -> {
            ((LlmModelConfig) inv.getArgument(0)).setId(10L);
            return 1;
        }).when(configMapper).insert(any());

        Long id = service.create(new LlmViews.ModelCreateRequest("qwen-max", "通义千问", "qwen",
                "https://x", "sk-1", "qwen-max", new BigDecimal("0.2"), 4096, null), ctx());

        assertEquals(10L, id);
        ArgumentCaptor<LlmModelVersion> vc = ArgumentCaptor.forClass(LlmModelVersion.class);
        verify(versionMapper).insert(vc.capture());
        assertEquals(1, vc.getValue().getVersionNo());
        assertEquals("PENDING", vc.getValue().getApplyResult());
        assertEquals(10L, vc.getValue().getConfigId());
        ArgumentCaptor<LlmDeployState> sc = ArgumentCaptor.forClass(LlmDeployState.class);
        verify(deployStateMapper).insert(sc.capture());
        assertEquals("PENDING", sc.getValue().getState());
        ArgumentCaptor<com.hrchat.audit.model.AuditEvent> ac =
                ArgumentCaptor.forClass(com.hrchat.audit.model.AuditEvent.class);
        verify(auditCollector).record(ac.capture());
        assertEquals(AuditEvents.LLM_CONFIG_CHANGE, ac.getValue().eventType());
        assertEquals("hr01", ac.getValue().userNo());
    }

    @Test
    void create_duplicateCode_throwsParamInvalid() {
        when(configMapper.selectCount(any())).thenReturn(1L);
        BizException ex = assertThrows(BizException.class, () -> service.create(
                new LlmViews.ModelCreateRequest("qwen-max", "通义千问", "qwen", null, null, null,
                        null, null, null), ctx()));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
        assertEquals("modelCode", ex.getArgs()[0]);
    }

    @Test
    void create_blankCode_throwsParamInvalid() {
        BizException ex = assertThrows(BizException.class, () -> service.create(
                new LlmViews.ModelCreateRequest(" ", "通义千问", "qwen", null, null, null,
                        null, null, null), ctx()));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
    }

    @Test
    void list_ok_withDeployStateAndVersionCount() {
        LlmModelConfig c1 = config("qwen-max", "通义千问");
        c1.setUpdatedAt(LocalDateTime.of(2026, 9, 28, 10, 0));
        LlmModelConfig c2 = config("gpt-4o", "GPT-4o");
        c2.setUpdatedAt(LocalDateTime.of(2026, 9, 28, 9, 0));
        when(configMapper.selectList(any())).thenReturn(List.of(c1, c2));
        LlmDeployState s1 = new LlmDeployState();
        s1.setConfigId(1L);
        s1.setState("ACTIVE");
        s1.setHealthStatus("UP");
        when(deployStateMapper.selectOne(any())).thenReturn(s1, (LlmDeployState) null);
        when(versionMapper.selectCount(any())).thenReturn(3L);

        com.hrchat.common.api.PageResult<LlmViews.ModelView> page = service.list(null, 1, 20);

        assertEquals(2, page.getTotal());
        LlmViews.ModelView v0 = page.getRecords().get(0);
        assertEquals("qwen-max", v0.modelCode());
        assertEquals("ACTIVE", v0.deployState());
        assertEquals("UP", v0.healthStatus());
        assertEquals(3, v0.versionCount());
        assertEquals("PENDING", page.getRecords().get(1).deployState());
    }

    @Test
    void list_keywordFilters() {
        when(configMapper.selectList(any())).thenReturn(List.of(config("qwen-max", "通义千问")));
        com.hrchat.common.api.PageResult<LlmViews.ModelView> page = service.list("通义", 1, 20);
        assertEquals(1, page.getTotal());
        assertEquals("qwen-max", page.getRecords().get(0).modelCode());
    }

    @Test
    void detail_masksApiKey() {
        LlmModelConfig c = config("qwen-max", "通义千问");
        c.setApiKey("sk-test12345678");
        c.setCreatedAt(LocalDateTime.of(2026, 9, 28, 10, 0));
        when(configMapper.selectById(1L)).thenReturn(c);
        LlmViews.ModelDetailView d = service.detail(1L);
        assertEquals("sk-****5678", d.apiKeyMasked());
        assertEquals("qwen-max", d.modelCode());
    }

    @Test
    void detail_blankApiKey_masksToNull() {
        LlmModelConfig c = config("qwen-max", "通义千问");
        c.setApiKey("");
        when(configMapper.selectById(1L)).thenReturn(c);
        assertNull(service.detail(1L).apiKeyMasked());
    }

    @Test
    void detail_missing_throwsParamInvalid() {
        when(configMapper.selectById(99L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.detail(99L));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
    }

    @Test
    void patch_updatesFieldsAndCreatesNewVersion() {
        LlmModelConfig c = config("qwen-max", "通义千问");
        c.setBaseUrl("https://old");
        when(configMapper.selectById(1L)).thenReturn(c);
        LlmModelVersion v2 = new LlmModelVersion();
        v2.setConfigId(1L);
        v2.setVersionNo(2);
        when(versionMapper.selectList(any())).thenReturn(List.of(v2));
        LlmDeployState state = new LlmDeployState();
        state.setConfigId(1L);
        state.setState("ACTIVE");
        when(deployStateMapper.selectOne(any())).thenReturn(state);

        service.patch(1L, new LlmViews.ModelCreateRequest(null, "通义千问Pro", "qwen", "https://new",
                null, "qwen-max", new BigDecimal("0.3"), 8192, "http://deploy"), ctx());

        ArgumentCaptor<LlmModelConfig> cc = ArgumentCaptor.forClass(LlmModelConfig.class);
        verify(configMapper).updateById(cc.capture());
        assertEquals("https://new", cc.getValue().getBaseUrl());
        assertEquals("通义千问Pro", cc.getValue().getModelName());
        ArgumentCaptor<LlmModelVersion> vc = ArgumentCaptor.forClass(LlmModelVersion.class);
        verify(versionMapper).insert(vc.capture());
        assertEquals(3, vc.getValue().getVersionNo());
        assertEquals("PENDING", vc.getValue().getApplyResult());
        verify(deployStateMapper).updateById(org.mockito.ArgumentMatchers.argThat(
                s -> "PENDING".equals(s.getState())));
    }

    @Test
    void patch_missing_throwsParamInvalid() {
        when(configMapper.selectById(1L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.patch(1L,
                new LlmViews.ModelCreateRequest(null, null, null, null, null, null, null, null, null), ctx()));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
    }

    @Test
    void delete_logicalDeleteAndAudit() {
        LlmModelConfig c = config("qwen-max", "通义千问");
        when(configMapper.selectById(1L)).thenReturn(c);
        service.delete(1L, ctx());
        verify(configMapper).updateById(org.mockito.ArgumentMatchers.argThat(
                x -> x.getIsDeleted() != null && x.getIsDeleted() == 1));
        verify(auditCollector).record(org.mockito.ArgumentMatchers.argThat(
                e -> AuditEvents.LLM_CONFIG_CHANGE.equals(e.eventType())));
    }

    @Test
    void versions_descOrder() {
        LlmModelVersion v1 = new LlmModelVersion();
        v1.setId(1L);
        v1.setConfigId(1L);
        v1.setVersionNo(1);
        v1.setConfigJson("{}");
        v1.setApplyResult("PENDING");
        LlmModelVersion v2 = new LlmModelVersion();
        v2.setId(2L);
        v2.setConfigId(1L);
        v2.setVersionNo(2);
        v2.setConfigJson("{}");
        v2.setApplyResult("SUCCESS");
        when(versionMapper.selectList(any())).thenReturn(List.of(v2, v1));

        List<LlmViews.VersionView> views = service.versions(1L);

        assertEquals(2, views.size());
        assertEquals(2, views.get(0).versionNo());
        assertEquals("SUCCESS", views.get(0).applyResult());
        assertTrue(views.get(1).configJson().equals("{}"));
    }

    // ---------------- P2 租户级 ----------------

    @Test
    void create_writesTenantFromContext() {
        when(configMapper.selectCount(any())).thenReturn(0L);
        doAnswer(inv -> {
            ((LlmModelConfig) inv.getArgument(0)).setId(11L);
            return 1;
        }).when(configMapper).insert(any());

        try {
            TenantContextHolder.set("t02");
            service.create(new LlmViews.ModelCreateRequest("t02-model", "租户B模型", "qwen",
                    null, null, null, null, null, null), ctx());
        } finally {
            TenantContextHolder.clear();
        }

        ArgumentCaptor<LlmModelConfig> cc = ArgumentCaptor.forClass(LlmModelConfig.class);
        verify(configMapper).insert(cc.capture());
        assertEquals("t02", cc.getValue().getTenantId());
    }

    @Test
    void create_writesDefaultTenant_whenNoHeader() {
        when(configMapper.selectCount(any())).thenReturn(0L);
        doAnswer(inv -> {
            ((LlmModelConfig) inv.getArgument(0)).setId(12L);
            return 1;
        }).when(configMapper).insert(any());

        service.create(new LlmViews.ModelCreateRequest("t01-model", "默认租户模型", "qwen",
                null, null, null, null, null, null), ctx());

        ArgumentCaptor<LlmModelConfig> cc = ArgumentCaptor.forClass(LlmModelConfig.class);
        verify(configMapper).insert(cc.capture());
        assertEquals("t01", cc.getValue().getTenantId());
    }

    @Test
    void list_appendsTenantFilter_whenHeaderPresent() {
        try {
            TenantContextHolder.set("t02");
            service.list(null, 1, 20);
        } finally {
            TenantContextHolder.clear();
        }
        ArgumentCaptor<LambdaQueryWrapper<LlmModelConfig>> wc = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(configMapper).selectList(wc.capture());
        assertTrue(wc.getValue().getCustomSqlSegment().contains("tenant_id"));
    }

    @Test
    void list_noTenantHeader_noTenantFilter() {
        service.list(null, 1, 20);
        ArgumentCaptor<LambdaQueryWrapper<LlmModelConfig>> wc = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(configMapper).selectList(wc.capture());
        assertTrue(!wc.getValue().getCustomSqlSegment().contains("tenant_id"));
    }

    @Test
    void detail_otherTenant_throwsFuncForbidden() {
        LlmModelConfig c = config("qwen-max", "通义千问");
        c.setTenantId("t01");
        when(configMapper.selectById(1L)).thenReturn(c);
        try {
            TenantContextHolder.set("t02");
            BizException ex = assertThrows(BizException.class, () -> service.detail(1L));
            assertEquals(ErrorCode.FUNC_FORBIDDEN, ex.getErrorCode());
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test
    void detail_sameTenant_ok() {
        LlmModelConfig c = config("qwen-max", "通义千问");
        c.setTenantId("t02");
        when(configMapper.selectById(1L)).thenReturn(c);
        try {
            TenantContextHolder.set("t02");
            assertEquals("qwen-max", service.detail(1L).modelCode());
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test
    void delete_otherTenant_throwsFuncForbidden() {
        LlmModelConfig c = config("qwen-max", "通义千问");
        c.setTenantId("t01");
        when(configMapper.selectById(1L)).thenReturn(c);
        try {
            TenantContextHolder.set("t02");
            BizException ex = assertThrows(BizException.class, () -> service.delete(1L, ctx()));
            assertEquals(ErrorCode.FUNC_FORBIDDEN, ex.getErrorCode());
        } finally {
            TenantContextHolder.clear();
        }
    }

    private LlmModelConfig config(String code, String name) {
        LlmModelConfig c = new LlmModelConfig();
        c.setId(1L);
        c.setModelCode(code);
        c.setModelName(name);
        c.setVendor("qwen");
        c.setModel("qwen-max");
        c.setTemperature(new BigDecimal("0.2"));
        c.setMaxTokens(4096);
        c.setStatus(1);
        c.setIsDeleted(0);
        return c;
    }
}
