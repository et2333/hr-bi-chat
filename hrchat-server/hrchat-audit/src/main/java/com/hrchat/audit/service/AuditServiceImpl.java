package com.hrchat.audit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.audit.entity.AudAuditLog;
import com.hrchat.audit.mapper.AudAuditLogMapper;
import com.hrchat.audit.model.AuditEvent;
import com.hrchat.authz.entity.SecUser;
import com.hrchat.authz.mapper.SecUserMapper;
import com.hrchat.authz.tenant.TenantContextHolder;
import com.hrchat.common.api.PageResult;
import com.hrchat.common.context.TraceContext;
import com.hrchat.common.exception.BizException;
import com.hrchat.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 审计服务实现：事件落库（BR-11 ≥3 年留痕）+ 检索（接口文档 2.6）。
 *
 * <p>检索约束：单次时间跨度 ≤31 天；{@code sensitive_only} 过滤敏感操作；
 * 仅管理员与审计角色可查（控制器按 {@code admin:audit:read} 裁决）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditServiceImpl implements AuditCollector {

    /** 单次检索最大时间跨度（天） */
    public static final int MAX_SPAN_DAYS = 31;

    private final AudAuditLogMapper auditLogMapper;
    private final SecUserMapper secUserMapper;
    private final ObjectMapper objectMapper;

    @Override
    public void record(AuditEvent event) {
        try {
            AudAuditLog log = new AudAuditLog();
            log.setTraceId(event.traceId() == null || event.traceId().isBlank()
                    ? TraceContext.currentTraceId() : event.traceId());
            log.setEventType(event.eventType());
            log.setUserNo(event.userNo());
            log.setActionTime(LocalDateTime.now());
            log.setObjectType(event.objectType());
            log.setObjectId(event.objectId());
            log.setDetailJson(event.detailJson());
            log.setIsSensitive(event.sensitive() ? 1 : 0);
            log.setIpAddr(currentIp());
            log.setTenantId(TenantContextHolder.get());
            auditLogMapper.insert(log);
        } catch (Exception e) {
            // 审计写入失败不阻断主流程（BR-11 尽力而为 + 告警）
            log.error("审计落库失败: eventType={}, userNo={}, traceId={}",
                    event.eventType(), event.userNo(), TraceContext.currentTraceId(), e);
        }
    }

    /**
     * 审计日志检索。
     *
     * @param userNo        操作人工号（精确）
     * @param startTime     起始时间（含）
     * @param endTime       结束时间（含）
     * @param eventType     事件类型
     * @param sensitiveOnly 仅敏感操作
     */
    public PageResult<AuditLogView> search(String userNo, LocalDateTime startTime, LocalDateTime endTime,
                                           String eventType, Boolean sensitiveOnly, int page, int size) {
        if (startTime != null && endTime != null
                && Duration.between(startTime, endTime).toDays() > MAX_SPAN_DAYS) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "单次检索时间跨度≤" + MAX_SPAN_DAYS + "天");
        }
        LambdaQueryWrapper<AudAuditLog> wrapper = new LambdaQueryWrapper<>();
        // 仅显式租户请求（X-Tenant-No）时按租户过滤，无租户头=单租户兼容视图
        String tenantId = TenantContextHolder.get();
        if (tenantId != null && !tenantId.isBlank()) {
            wrapper.eq(AudAuditLog::getTenantId, tenantId);
        }
        if (userNo != null && !userNo.isBlank()) {
            wrapper.eq(AudAuditLog::getUserNo, userNo.trim());
        }
        if (eventType != null && !eventType.isBlank()) {
            wrapper.eq(AudAuditLog::getEventType, eventType.trim());
        }
        if (Boolean.TRUE.equals(sensitiveOnly)) {
            wrapper.eq(AudAuditLog::getIsSensitive, 1);
        }
        if (startTime != null) {
            wrapper.ge(AudAuditLog::getActionTime, startTime);
        }
        if (endTime != null) {
            wrapper.le(AudAuditLog::getActionTime, endTime);
        }
        wrapper.orderByDesc(AudAuditLog::getActionTime);

        List<AudAuditLog> all = auditLogMapper.selectList(wrapper);
        List<AuditLogView> records = all.stream()
                .skip((long) (page - 1) * size).limit(size)
                .map(this::toView)
                .toList();
        return PageResult.of(records, all.size(), page, size);
    }

    private AuditLogView toView(AudAuditLog log) {
        String userName = null;
        try {
            SecUser user = secUserMapper.selectOne(new LambdaQueryWrapper<SecUser>()
                    .eq(SecUser::getEmpNo, log.getUserNo()).last("LIMIT 1"));
            if (user != null) {
                userName = user.getDisplayName();
            }
        } catch (Exception e) {
            // 姓名解析失败不影响检索结果
        }
        AuditLogView base = AuditLogView.of(log, userName);
        Map<String, Object> detail = parseDetail(log.getDetailJson());
        return new AuditLogView(base.logId(), base.userNo(), base.userName(), base.action(),
                base.resource(),
                detail == null ? null : String.valueOf(detail.getOrDefault("question", "")),
                detail == null ? null : String.valueOf(detail.getOrDefault("sql_digest", "")),
                detail == null || detail.get("rows") == null ? null
                        : ((Number) detail.get("rows")).longValue(),
                detail == null ? null : String.valueOf(detail.getOrDefault("file_name", "")),
                base.ip(), base.sensitive(), base.ts());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseDetail(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return objectMapper.convertValue(node, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String currentIp() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest request = attrs.getRequest();
                String ip = request.getHeader("X-Forwarded-For");
                return (ip == null || ip.isBlank()) ? request.getRemoteAddr() : ip;
            }
        } catch (Exception ignored) {
            // 无 Web 上下文（异步任务）返回空
        }
        return null;
    }
}
