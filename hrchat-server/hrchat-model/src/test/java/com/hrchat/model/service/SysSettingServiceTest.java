package com.hrchat.model.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.audit.service.AuditCollector;
import com.hrchat.authz.model.UserContext;
import com.hrchat.model.entity.SysSetting;
import com.hrchat.model.mapper.SysSettingMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SysSettingService 单测：读取默认值回退、upsert 新建/更新、删除幂等。 */
class SysSettingServiceTest {

    private final SysSettingMapper settingMapper = mock(SysSettingMapper.class);
    private final AuditCollector auditCollector = mock(AuditCollector.class);
    private final SysSettingService service = new SysSettingService(settingMapper, auditCollector, new ObjectMapper());

    private UserContext ctx;

    @BeforeEach
    void setUp() {
        ctx = UserContext.builder().empNo("adm01").build();
    }

    @Test
    void get_returnsValue_whenConfigured() {
        SysSetting setting = new SysSetting();
        setting.setSettingKey("llm.default.profile");
        setting.setSettingValue("openai");
        when(settingMapper.selectOne(any())).thenReturn(setting);

        assertThat(service.get("llm.default.profile", "mock")).isEqualTo("openai");
    }

    @Test
    void get_fallsBackToDefault_whenAbsentOrBlank() {
        when(settingMapper.selectOne(any())).thenReturn(null);
        assertThat(service.get("llm.default.profile", "mock")).isEqualTo("mock");

        SysSetting blank = new SysSetting();
        blank.setSettingValue(" ");
        when(settingMapper.selectOne(any())).thenReturn(blank);
        assertThat(service.get("llm.default.profile", "mock")).isEqualTo("mock");
    }

    @Test
    void upsert_createsNew_whenKeyAbsent() {
        when(settingMapper.selectOne(any())).thenReturn(null);
        service.upsert("deploy.mock", "false", "关闭模拟部署", ctx);

        ArgumentCaptor<SysSetting> captor = ArgumentCaptor.forClass(SysSetting.class);
        verify(settingMapper).insert(captor.capture());
        assertThat(captor.getValue().getSettingKey()).isEqualTo("deploy.mock");
        assertThat(captor.getValue().getSettingValue()).isEqualTo("false");
        assertThat(captor.getValue().getCreatedBy()).isEqualTo("adm01");
        verify(auditCollector).record(any());
    }

    @Test
    void upsert_updatesExisting_whenKeyPresent() {
        SysSetting existing = new SysSetting();
        existing.setId(5L);
        existing.setSettingKey("deploy.mock");
        existing.setSettingValue("true");
        when(settingMapper.selectOne(any())).thenReturn(existing);

        service.upsert("deploy.mock", "false", "模拟部署开关", ctx);

        verify(settingMapper).updateById(existing);
        assertThat(existing.getSettingValue()).isEqualTo("false");
        assertThat(existing.getUpdatedBy()).isEqualTo("adm01");
        verify(settingMapper, never()).insert(any());
    }

    @Test
    void delete_isIdempotent() {
        when(settingMapper.selectOne(any())).thenReturn(null);
        service.delete("deploy.mock", ctx);
        verify(settingMapper, never()).deleteById((java.io.Serializable) any());
        verify(auditCollector, never()).record(any());
    }

    @Test
    void delete_removesExisting() {
        SysSetting existing = new SysSetting();
        existing.setId(5L);
        existing.setSettingKey("deploy.mock");
        when(settingMapper.selectOne(any())).thenReturn(existing);

        service.delete("deploy.mock", ctx);

        verify(settingMapper).deleteById((java.io.Serializable) 5L);
        verify(auditCollector).record(any());
    }
}
