package com.hrchat.authz.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/** 角色功能授权；权限码目录由 PermissionCatalog 定义。 */
@Data
@TableName("sec_role_permission")
public class SecRolePermission {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String roleCode;
    private String permissionCode;
    private LocalDateTime createdAt;
    private String createdBy;
}
