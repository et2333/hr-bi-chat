package com.hrchat.admin.user;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户管理视图模型（接口文档 2.5 用户管理）。
 */
public final class UserViews {

    private UserViews() {
    }

    /** 创建用户请求。 */
    public record UserCreateRequest(String empNo, String displayName, String email,
                                    Long orgNodeId, List<String> roleCodes) {
    }

    /** 用户列表项（含角色码列表 sec_user_role 联查）。 */
    public record UserView(Long id, String empNo, String displayName, String email,
                           Long orgNodeId, String orgName, Integer status, String tenantId,
                           Integer mustChangePwd, List<String> roles,
                           LocalDateTime lastLoginAt, LocalDateTime createdAt) {
    }

    /** 启用/停用请求体。 */
    public record StatusRequest(Integer status) {
    }

    /** 角色授予请求体。 */
    public record RoleAssignRequest(List<String> roleCodes) {
    }

    /** 创建成功视图（initialPassword 仅此一次返回，SSO 对接后废弃，当前仅演示）。 */
    public record CreateUserResultView(Long userId, String initialPassword) {
    }

    /** 重置密码成功视图（initialPassword 仅此一次返回，SSO 对接后废弃，当前仅演示）。 */
    public record ResetResultView(Long userId, String initialPassword) {
    }
}
