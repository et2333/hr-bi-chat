package com.hrchat.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.authz.model.UserContext;
import com.hrchat.chat.entity.SysIdempotencyRecord;
import com.hrchat.chat.mapper.SysIdempotencyRecordMapper;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IdempotencyServiceTest {

    private final SysIdempotencyRecordMapper mapper = Mockito.mock(SysIdempotencyRecordMapper.class);
    private final IdempotencyService service = new IdempotencyService(mapper, new ObjectMapper());
    private UserContext context;

    record Result(String askId) {
    }

    @BeforeEach
    void setUp() {
        context = UserContext.builder().userId(1L).empNo("hr01").tenantId("t01").build();
    }

    @Test
    void missingKey_executesWithoutPersistence() {
        var result = service.execute(context, "POST", "/asks", "1", null,
                "request", Result.class, () -> new Result("ask-1"));

        assertThat(result.replayed()).isFalse();
        assertThat(result.value().askId()).isEqualTo("ask-1");
        verifyNoInteractions(mapper);
    }

    @Test
    void firstRequest_registersBeforeActionAndStoresResponse() {
        AtomicInteger calls = new AtomicInteger();

        var result = service.execute(context, "POST", "/asks", "1", "key-1",
                "request", Result.class, () -> {
                    calls.incrementAndGet();
                    return new Result("ask-1");
                });

        assertThat(result.replayed()).isFalse();
        assertThat(calls).hasValue(1);
        ArgumentCaptor<SysIdempotencyRecord> captor = ArgumentCaptor.forClass(SysIdempotencyRecord.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo("t01");
        assertThat(captor.getValue().getStatus()).isEqualTo(IdempotencyService.COMPLETED);
        assertThat(captor.getValue().getResponseBody()).contains("ask-1");
        verify(mapper).updateById(captor.getValue());
    }

    @Test
    void completedDuplicate_replaysStoredResponseWithoutAction() {
        doThrow(new DuplicateKeyException("duplicate")).when(mapper).insert(any());
        SysIdempotencyRecord existing = existing(IdempotencyService.COMPLETED, "\"request\"");
        existing.setResponseBody("{\"askId\":\"ask-existing\"}");
        when(mapper.selectOne(any())).thenReturn(existing);
        AtomicInteger calls = new AtomicInteger();

        var result = service.execute(context, "POST", "/asks", "1", "key-1",
                "request", Result.class, () -> {
                    calls.incrementAndGet();
                    return new Result("must-not-run");
                });

        assertThat(result.replayed()).isTrue();
        assertThat(result.value().askId()).isEqualTo("ask-existing");
        assertThat(calls).hasValue(0);
    }

    @Test
    void sameKeyWithDifferentRequest_isRejected() {
        doThrow(new DuplicateKeyException("duplicate")).when(mapper).insert(any());
        SysIdempotencyRecord existing = existing(IdempotencyService.COMPLETED, "\"other-request\"");
        when(mapper.selectOne(any())).thenReturn(existing);

        assertThatThrownBy(() -> service.execute(context, "POST", "/asks", "1", "key-1",
                "request", Result.class, () -> new Result("ask-1")))
                .isInstanceOf(BizException.class)
                .extracting(error -> ((BizException) error).getErrorCode())
                .isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void duplicateWhileProcessing_isRejected() {
        doThrow(new DuplicateKeyException("duplicate")).when(mapper).insert(any());
        when(mapper.selectOne(any())).thenReturn(existing(IdempotencyService.PROCESSING, "\"request\""));

        assertThatThrownBy(() -> service.execute(context, "POST", "/asks", "1", "key-1",
                "request", Result.class, () -> new Result("ask-1")))
                .isInstanceOf(BizException.class)
                .extracting(error -> ((BizException) error).getErrorCode())
                .isEqualTo(ErrorCode.IDEMPOTENT_PROCESSING);
    }

    @Test
    void concurrentDuplicate_executesActionOnlyOnce() throws Exception {
        AtomicReference<SysIdempotencyRecord> stored = new AtomicReference<>();
        AtomicInteger inserts = new AtomicInteger();
        when(mapper.insert(any())).thenAnswer(invocation -> {
            SysIdempotencyRecord candidate = invocation.getArgument(0);
            if (inserts.getAndIncrement() == 0) {
                candidate.setId(1L);
                stored.set(candidate);
                return 1;
            }
            throw new DuplicateKeyException("duplicate");
        });
        when(mapper.selectOne(any())).thenAnswer(invocation -> stored.get());
        CountDownLatch actionStarted = new CountDownLatch(1);
        CountDownLatch releaseAction = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();

        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> service.execute(context, "POST", "/asks", "1", "key-1",
                    "request", Result.class, () -> {
                        calls.incrementAndGet();
                        actionStarted.countDown();
                        try {
                            releaseAction.await(2, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                        return new Result("ask-1");
                    }));
            assertThat(actionStarted.await(1, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> service.execute(context, "POST", "/asks", "1", "key-1",
                    "request", Result.class, () -> {
                        calls.incrementAndGet();
                        return new Result("must-not-run");
                    }));

            assertThatThrownBy(() -> second.get(1, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(BizException.class);
            releaseAction.countDown();
            assertThat(first.get(1, TimeUnit.SECONDS).value().askId()).isEqualTo("ask-1");
        } finally {
            releaseAction.countDown();
            executor.shutdownNow();
        }
        assertThat(calls).hasValue(1);
    }

    @Test
    void cleanupDeletesExpiredRecords() {
        service.cleanupExpired();
        verify(mapper).delete(any());
    }

    private SysIdempotencyRecord existing(String status, String serializedRequest) {
        SysIdempotencyRecord record = new SysIdempotencyRecord();
        record.setId(1L);
        record.setStatus(status);
        record.setRequestHash(sha256(serializedRequest));
        record.setAttemptCount(1);
        record.setCreatedAt(LocalDateTime.now());
        record.setUpdatedAt(LocalDateTime.now());
        return record;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : digest) {
                result.append(String.format("%02x", b));
            }
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
