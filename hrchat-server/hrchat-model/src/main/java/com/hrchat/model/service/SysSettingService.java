package com.hrchat.model.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.audit.model.AuditEvent;
import com.hrchat.audit.model.AuditEvents;
import com.hrchat.audit.service.AuditCollector;
import com.hrchat.authz.model.UserContext;
import com.hrchat.common.exception.BizException;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.model.entity.SysSetting;
import com.hrchat.model.mapper.SysSettingMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 系统设置（t_sys_setting）：键值读取（运行时）与增改删（管理台）。
 *
 * <p>{@code get()} 供 LLM 运行时默认档位等跨模块读取；{@code list/upsert/delete}
 * 由管理端 {@code SysSettingController} 暴露，操作记 {@code SYSTEM_SETTING_CHANGE} 审计。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysSettingService {

    /** 功能权限：系统设置管理 */
    public static final String PERM_MANAGE = "admin:system:manage";
    /** 功能权限：系统设置查看 */
    public static final String PERM_VIEW = "admin:system:view";

    /** 默认 LLM 档位键 */
    public static final String KEY_LLM_DEFAULT_PROFILE = "llm.default.profile";
    /** 模拟部署开关键 */
    public static final String KEY_DEPLOY_MOCK = "deploy.mock";

    private final SysSettingMapper settingMapper;
    private final AuditCollector auditCollector;
    private final ObjectMapper objectMapper;

    /**
     * 读取配置值；未配置或为空时返回默认值。
     */
    public String get(String key, String defaultVal) {
        SysSetting setting = settingMapper.selectOne(new LambdaQueryWrapper<SysSetting>()
                .eq(SysSetting::getSettingKey, key));
        if (setting == null || setting.getSettingValue() == null || setting.getSettingValue().isBlank()) {
            return defaultVal;
        }
        return setting.getSettingValue();
    }

    /**
     * 配置列表（按 key 升序）。
     */
    public List<SysSetting> list() {
        return settingMapper.selectList(new LambdaQueryWrapper<SysSetting>()
                .orderByAsc(SysSetting::getSettingKey));
    }

    /**
     * 新增或更新配置（按 key upsert），落 SYSTEM_SETTING_CHANGE 审计。
     */
    @Transactional
    public void upsert(String key, String value, String description, UserContext ctx) {
        if (key == null || key.isBlank()) {
            throw new BizException(ErrorCode.PARAM_MISSING, "setting_key");
        }
        if (value == null || value.isBlank()) {
            throw new BizException(ErrorCode.PARAM_MISSING, "setting_value");
        }
        String trimmedKey = key.trim();
        LocalDateTime now = LocalDateTime.now();
        SysSetting existing = settingMapper.selectOne(new LambdaQueryWrapper<SysSetting>()
                .eq(SysSetting::getSettingKey, trimmedKey));
        if (existing == null) {
            SysSetting setting = new SysSetting();
            setting.setSettingKey(trimmedKey);
            setting.setSettingValue(value.trim());
            setting.setDescription(description);
            setting.setCreatedAt(now);
            setting.setCreatedBy(ctx.getEmpNo());
            setting.setUpdatedAt(now);
            setting.setUpdatedBy(ctx.getEmpNo());
            settingMapper.insert(setting);
        } else {
            existing.setSettingValue(value.trim());
            existing.setDescription(description);
            existing.setUpdatedAt(now);
            existing.setUpdatedBy(ctx.getEmpNo());
            settingMapper.updateById(existing);
        }
        auditCollector.record(AuditEvent.of(AuditEvents.SYSTEM_SETTING_CHANGE, ctx.getEmpNo(),
                "sys_setting", trimmedKey,
                toJson(Map.of("key", trimmedKey, "op", existing == null ? "CREATE" : "UPDATE")), false));
        log.info("系统设置更新: key={}, operator={}", trimmedKey, ctx.getEmpNo());
    }

    /**
     * 删除配置（不存在则忽略），落 SYSTEM_SETTING_CHANGE 审计。
     */
    @Transactional
    public void delete(String key, UserContext ctx) {
        if (key == null || key.isBlank()) {
            throw new BizException(ErrorCode.PARAM_MISSING, "setting_key");
        }
        String trimmedKey = key.trim();
        SysSetting existing = settingMapper.selectOne(new LambdaQueryWrapper<SysSetting>()
                .eq(SysSetting::getSettingKey, trimmedKey));
        if (existing == null) {
            return;
        }
        settingMapper.deleteById(existing.getId());
        auditCollector.record(AuditEvent.of(AuditEvents.SYSTEM_SETTING_CHANGE, ctx.getEmpNo(),
                "sys_setting", trimmedKey,
                toJson(Map.of("key", trimmedKey, "op", "DELETE")), false));
        log.info("系统设置删除: key={}, operator={}", trimmedKey, ctx.getEmpNo());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
