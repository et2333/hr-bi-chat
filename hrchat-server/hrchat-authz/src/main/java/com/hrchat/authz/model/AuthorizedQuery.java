package com.hrchat.authz.model;

import java.util.List;
import java.util.Set;

/** 经过身份、租户、数据范围和 SQL 结构校验后才允许执行的查询。 */
public record AuthorizedQuery(
        String sql,
        List<Object> parameters,
        Set<String> referencedTables,
        String permissionFingerprint) {

    public AuthorizedQuery {
        parameters = List.copyOf(parameters);
        referencedTables = Set.copyOf(referencedTables);
    }
}
