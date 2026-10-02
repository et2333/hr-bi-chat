package com.hrchat.audit.model;

/**
 * 审计事件常量（接口文档 2.6 action 枚举 + 数据库文档 event_type 示例）。
 */
public final class AuditEvents {

    private AuditEvents() {
    }

    /** 登录 */
    public static final String LOGIN = "LOGIN";

    /** 问句（BR-02/FR-20：问句摘要、SQL 摘要、行数） */
    public static final String ASK = "ASK";

    /** MCP 工具调用（阶段 C：semantic_query 等，含 tool_call_id） */
    public static final String MCP_TOOL_CALL = "MCP_TOOL_CALL";

    /** 查看 SQL（独立授权，访问记审计） */
    public static final String VIEW_SQL = "VIEW_SQL";

    /** 导出（敏感操作，BR-11） */
    public static final String EXPORT = "EXPORT";

    /** 明文查看（敏感操作，BR-11） */
    public static final String PLAIN_VIEW = "PLAIN_VIEW";

    /** 越权拦截（HRC-2002/HRC-2003，BR-02） */
    public static final String PERM_DENIED = "PERM_DENIED";

    /** 权限变更（BR-12 留痕） */
    public static final String PERMISSION_CHANGE = "PERMISSION_CHANGE";

    /** 语义层变更（口径审批/发布，BR-04） */
    public static final String SEMANTIC_CHANGE = "SEMANTIC_CHANGE";

    /** 同步任务手动重试 */
    public static final String SYNC_RETRY = "SYNC_RETRY";

    /** LLM 模型配置变更（创建/更新/删除） */
    public static final String LLM_CONFIG_CHANGE = "LLM_CONFIG_CHANGE";

    /** LLM 模型部署/回滚 */
    public static final String LLM_DEPLOY = "LLM_DEPLOY";

    /** 用户创建（多租户管理，SSO 对接后废弃） */
    public static final String USER_CREATE = "USER_CREATE";

    /** 用户启用/停用 */
    public static final String USER_STATUS_CHANGE = "USER_STATUS_CHANGE";

    /** 用户角色授予 */
    public static final String USER_ROLE_GRANT = "USER_ROLE_GRANT";

    /** 密码重置（本地演示） */
    public static final String PWD_RESET = "PWD_RESET";

    /** 租户创建 */
    public static final String TENANT_CREATE = "TENANT_CREATE";

    /** 租户信息/配额更新 */
    public static final String TENANT_UPDATE = "TENANT_UPDATE";

    /** 租户启用/停用 */
    public static final String TENANT_CHANGE = "TENANT_CHANGE";

    /** 系统设置变更（t_sys_setting 键值增改删） */
    public static final String SYSTEM_SETTING_CHANGE = "SYSTEM_SETTING_CHANGE";
}
