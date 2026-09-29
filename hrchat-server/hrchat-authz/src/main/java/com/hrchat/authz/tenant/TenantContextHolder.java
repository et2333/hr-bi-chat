package com.hrchat.authz.tenant;

/**
 * 租户上下文：一次请求内的租户事实源（仅显式请求头 {@code X-Tenant-No} 时注入）。
 *
 * <p>语义约定：{@code get()} 返回 {@code null} 表示「未显式指定租户」= 单租户兼容视图。
 * 各业务查询仅在 get() 非空时追加 {@code tenant_id} 过滤，从而保证既有单租户行为与测试不回归。</p>
 */
public final class TenantContextHolder {

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private TenantContextHolder() {
    }

    /**
     * 当前租户号；未显式设置时返回 {@code null}（单租户兼容视图）。
     *
     * @return 租户号或 {@code null}
     */
    public static String get() {
        return HOLDER.get();
    }

    /**
     * 显式设置当前租户号（由 {@link TenantFilter} 在请求头存在时调用）。
     *
     * @param tenantNo 租户号
     */
    public static void set(String tenantNo) {
        HOLDER.set(tenantNo);
    }

    /** 清理当前线程租户上下文（请求结束 finally 调用）。 */
    public static void clear() {
        HOLDER.remove();
    }
}
