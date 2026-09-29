package com.hrchat.authz.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TenantContextHolder 单测：默认未设置返回 null（单租户兼容视图），set/get/clear 语义。
 */
class TenantContextHolderTest {

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void get_returnsNullWhenUnset() {
        TenantContextHolder.clear();
        assertThat(TenantContextHolder.get()).isNull();
    }

    @Test
    void set_thenGet_returnsValue() {
        TenantContextHolder.set("t02");
        assertThat(TenantContextHolder.get()).isEqualTo("t02");
    }

    @Test
    void clear_removesValue() {
        TenantContextHolder.set("t01");
        TenantContextHolder.clear();
        assertThat(TenantContextHolder.get()).isNull();
    }

    @Test
    void setNull_thenGet_returnsNull() {
        TenantContextHolder.set(null);
        assertThat(TenantContextHolder.get()).isNull();
    }

    @Test
    void threadsAreIsolated() throws InterruptedException {
        TenantContextHolder.set("t01");
        Thread other = new Thread(() -> {
            assertThat(TenantContextHolder.get()).isNull();
            TenantContextHolder.set("t02");
        });
        other.start();
        other.join();
        assertThat(TenantContextHolder.get()).isEqualTo("t01");
    }
}
