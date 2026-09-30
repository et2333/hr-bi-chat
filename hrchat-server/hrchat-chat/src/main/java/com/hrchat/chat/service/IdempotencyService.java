package com.hrchat.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.authz.model.UserContext;
import com.hrchat.chat.entity.SysIdempotencyRecord;
import com.hrchat.chat.mapper.SysIdempotencyRecordMapper;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.common.exception.BizException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** 基于数据库唯一索引的可复用幂等执行组件。 */
@Service
public class IdempotencyService {

    static final String PROCESSING = "PROCESSING";
    static final String COMPLETED = "COMPLETED";
    static final String FAILED_FINAL = "FAILED_FINAL";
    static final String FAILED_RETRYABLE = "FAILED_RETRYABLE";

    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private final SysIdempotencyRecordMapper mapper;
    private final ObjectMapper objectMapper;
    private final int retryCooldownSeconds;
    private final int maxAttempts;
    private final int ttlHours;

    @Autowired
    public IdempotencyService(SysIdempotencyRecordMapper mapper, ObjectMapper objectMapper,
                              @Value("${hrchat.idempotency.retry-cooldown-seconds:30}") int retryCooldownSeconds,
                              @Value("${hrchat.idempotency.max-attempts:3}") int maxAttempts,
                              @Value("${hrchat.idempotency.ttl-hours:24}") int ttlHours) {
        if (retryCooldownSeconds < 1 || maxAttempts < 1 || ttlHours < 1) {
            throw new IllegalArgumentException("幂等冷却时间、最大尝试次数和 TTL 必须为正数");
        }
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.retryCooldownSeconds = retryCooldownSeconds;
        this.maxAttempts = maxAttempts;
        this.ttlHours = ttlHours;
    }

    IdempotencyService(SysIdempotencyRecordMapper mapper, ObjectMapper objectMapper) {
        this(mapper, objectMapper, 30, 3, 24);
    }

    /** 幂等执行结果。 */
    public record Execution<T>(T value, boolean replayed) {
    }

    /** 定期删除已过期记录，避免唯一索引让旧幂等键永久占用。 */
    @Scheduled(fixedDelayString = "${hrchat.idempotency.cleanup-interval-ms:3600000}",
            initialDelayString = "${hrchat.idempotency.cleanup-initial-delay-ms:3600000}")
    public void cleanupExpired() {
        mapper.delete(new LambdaQueryWrapper<SysIdempotencyRecord>()
                .lt(SysIdempotencyRecord::getExpiresAt, LocalDateTime.now()));
    }

    /**
     * 执行或回放一次创建类操作。未提供幂等键时保持兼容，直接执行。
     */
    public <T> Execution<T> execute(UserContext ctx, String method, String endpoint, String resourceId,
                                    String idempotencyKey, Object request, Class<T> responseType,
                                    Supplier<T> action) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return new Execution<>(action.get(), false);
        }
        String key = idempotencyKey.trim();
        if (!SAFE_KEY.matcher(key).matches()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "X-Idempotency-Key");
        }
        String requestHash = hash(toJson(request));
        String scopeKey = hash(String.join("|", ctx.getTenantId(), String.valueOf(ctx.getUserId()),
                method, endpoint, resourceId, key));

        SysIdempotencyRecord record = newRecord(ctx, endpoint, resourceId, key, requestHash, scopeKey);
        try {
            mapper.insert(record);
        } catch (DuplicateKeyException ex) {
            return handleExisting(scopeKey, requestHash, responseType, action);
        }
        return runFirst(record, responseType, action);
    }

    private <T> Execution<T> handleExisting(String scopeKey, String requestHash, Class<T> responseType,
                                            Supplier<T> action) {
        SysIdempotencyRecord existing = mapper.selectOne(new LambdaQueryWrapper<SysIdempotencyRecord>()
                .eq(SysIdempotencyRecord::getScopeKey, scopeKey));
        if (existing == null) {
            throw new BizException(ErrorCode.SYSTEM_BUSY);
        }
        if (!requestHash.equals(existing.getRequestHash())) {
            throw new BizException(ErrorCode.IDEMPOTENCY_CONFLICT);
        }
        if (COMPLETED.equals(existing.getStatus())) {
            return new Execution<>(fromJson(existing.getResponseBody(), responseType), true);
        }
        if (FAILED_FINAL.equals(existing.getStatus())) {
            throw replayFailure(existing);
        }
        if (FAILED_RETRYABLE.equals(existing.getStatus()) && mayRetry(existing)) {
            int updated = mapper.update(null, new LambdaUpdateWrapper<SysIdempotencyRecord>()
                    .eq(SysIdempotencyRecord::getId, existing.getId())
                    .eq(SysIdempotencyRecord::getStatus, FAILED_RETRYABLE)
                    .set(SysIdempotencyRecord::getStatus, PROCESSING)
                    .set(SysIdempotencyRecord::getAttemptCount, existing.getAttemptCount() + 1)
                    .set(SysIdempotencyRecord::getUpdatedAt, LocalDateTime.now()));
            if (updated == 1) {
                existing.setStatus(PROCESSING);
                existing.setAttemptCount(existing.getAttemptCount() + 1);
                return runFirst(existing, responseType, action);
            }
        }
        throw new BizException(ErrorCode.IDEMPOTENT_PROCESSING);
    }

    private <T> Execution<T> runFirst(SysIdempotencyRecord record, Class<T> responseType,
                                      Supplier<T> action) {
        try {
            T value = action.get();
            record.setStatus(COMPLETED);
            record.setResponseBody(toJson(value));
            record.setResponseCode("SUCCESS");
            record.setUpdatedAt(LocalDateTime.now());
            mapper.updateById(record);
            return new Execution<>(value, false);
        } catch (BizException ex) {
            fail(record, ex.getErrorCode(), isRetryable(ex.getErrorCode()));
            throw ex;
        } catch (RuntimeException ex) {
            fail(record, ErrorCode.SYSTEM_BUSY, true);
            throw ex;
        }
    }

    private void fail(SysIdempotencyRecord record, ErrorCode errorCode, boolean retryable) {
        record.setStatus(retryable ? FAILED_RETRYABLE : FAILED_FINAL);
        record.setResponseCode(errorCode.name());
        record.setRetryAfter(retryable ? LocalDateTime.now().plusSeconds(retryCooldownSeconds) : null);
        record.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(record);
    }

    private boolean mayRetry(SysIdempotencyRecord record) {
        int attempts = record.getAttemptCount() == null ? 1 : record.getAttemptCount();
        return attempts < maxAttempts && record.getRetryAfter() != null
                && !record.getRetryAfter().isAfter(LocalDateTime.now());
    }

    private static boolean isRetryable(ErrorCode code) {
        return code == ErrorCode.SYSTEM_BUSY || code == ErrorCode.SERVICE_UNAVAILABLE
                || code == ErrorCode.RATE_LIMITED || code == ErrorCode.QUERY_TIMEOUT;
    }

    private static BizException replayFailure(SysIdempotencyRecord record) {
        try {
            return new BizException(ErrorCode.valueOf(record.getResponseCode()));
        } catch (RuntimeException ignored) {
            return new BizException(ErrorCode.SYSTEM_BUSY);
        }
    }

    private SysIdempotencyRecord newRecord(UserContext ctx, String endpoint, String resourceId,
                                           String key, String requestHash, String scopeKey) {
        LocalDateTime now = LocalDateTime.now();
        SysIdempotencyRecord record = new SysIdempotencyRecord();
        record.setScopeKey(scopeKey);
        record.setTenantId(ctx.getTenantId());
        record.setUserId(ctx.getUserId());
        record.setEndpoint(endpoint);
        record.setResourceId(resourceId);
        record.setIdempotencyKey(key);
        record.setRequestHash(requestHash);
        record.setStatus(PROCESSING);
        record.setAttemptCount(1);
        record.setExpiresAt(now.plusHours(ttlHours));
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        return record;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.SYSTEM_BUSY, e);
        }
    }

    private <T> T fromJson(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.SYSTEM_BUSY, e);
        }
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : digest) {
                result.append(String.format("%02x", b));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
