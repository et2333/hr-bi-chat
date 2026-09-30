package com.hrchat.queryexec.service;

import com.hrchat.authz.model.AuthorizedQuery;
import com.hrchat.queryexec.model.QueryResult;

/**
 * 取数执行器接口（架构文档 query-exec：Doris 执行；local 环境使用 H2 执行）。
 */
public interface QueryExecutor {

    /**
     * 执行只读 SQL。
     *
     * @param sql             已通过权限改写与只读校验的 SQL
     * @param timeoutSeconds  超时控制（秒），超时抛异常
     * @return 查询结果
     */
    QueryResult execute(AuthorizedQuery query, int timeoutSeconds);
}
