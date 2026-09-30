package com.hrchat.authz.service;
import java.util.Collection;
import java.util.List;

/** 程序支持的权限码；角色授权只从数据库读取。 */
public final class PermissionCatalog {
    private PermissionCatalog() { }
    public static final List<String> CODES = List.of(
            "admin:*",
            "admin:audit:read",
            "admin:authz:manage",
            "admin:data:manage",
            "admin:data:read",
            "admin:eval:manage",
            "admin:llm:manage",
            "admin:llm:view",
            "admin:push:manage",
            "admin:semantic",
            "admin:semantic:approve",
            "admin:system:manage",
            "admin:system:view",
            "admin:tenant:manage",
            "admin:tenant:switch",
            "admin:user:manage",
            "admin:view",
            "chat:ask",
            "chat:attribution",
            "chat:view_sql",
            "export:apply",
            "payroll:plain_view",
            "report:create",
            "report:manage",
            "report:subscribe",
            "report:view",
            "semantic:manage");

    public static boolean matches(Collection<String> permissions, String required) {
        return permissions != null && required != null && permissions.stream().anyMatch(p ->
                p.equals(required) || (p.endsWith(":*")
                && required.startsWith(p.substring(0, p.length() - 1))));
    }
}
