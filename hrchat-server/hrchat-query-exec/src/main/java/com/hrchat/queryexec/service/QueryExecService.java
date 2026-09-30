package com.hrchat.queryexec.service;

import com.hrchat.authz.model.AuthorizedQuery;
import com.hrchat.authz.service.SqlRewriteService;
import com.hrchat.queryexec.model.QueryResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 取数编排服务（架构文档 D-2：取数唯一入口经权限中心裁决后进入）。
 *
 * <p>纵深防御：无论上层是否已改写，本层再次执行只读白名单校验（BR-01），
 * 随后交执行器运行并施加超时控制。</p>
 */
@Service
@RequiredArgsConstructor
public class QueryExecService {

    /** 问数链路 SQL 超时（秒）。 */
    public static final int DEFAULT_TIMEOUT_SECONDS = 5;

    private final SqlRewriteService sqlRewriteService;
    private final QueryExecutor queryExecutor;

    /**
     * 执行经权限改写后的只读 SQL。
     *
     * @param sql    改写后的 SQL（含 {authz_org_filter} 已被替换）
     * @return 查询结果
     */
    @Transactional(readOnly = true)
    public QueryResult executeReadonly(AuthorizedQuery query) {
        sqlRewriteService.validateReadOnly(query.sql());
        return queryExecutor.execute(query, DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * 直接执行（供已自行校验的调用方使用，如分页续查）。
     */
    @Transactional(readOnly = true)
    public QueryResult executeWithTimeout(AuthorizedQuery query, int timeoutSeconds) {
        sqlRewriteService.validateReadOnly(query.sql());
        return queryExecutor.execute(query, timeoutSeconds);
    }

}
