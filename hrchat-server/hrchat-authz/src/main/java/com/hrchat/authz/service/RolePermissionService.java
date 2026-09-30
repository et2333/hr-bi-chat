package com.hrchat.authz.service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hrchat.authz.entity.SecRolePermission;
import com.hrchat.authz.mapper.SecRolePermissionMapper;
import com.hrchat.common.exception.BizException;
import com.hrchat.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Collection;
import java.util.List;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class RolePermissionService {
    private final SecRolePermissionMapper mapper;

    public List<String> forRoles(Collection<String> roles) {
        if (roles == null || roles.isEmpty()) return List.of();
        return mapper.selectList(new LambdaQueryWrapper<SecRolePermission>()
                .in(SecRolePermission::getRoleCode, roles)).stream()
                .map(SecRolePermission::getPermissionCode)
                .filter(PermissionCatalog.CODES::contains).distinct().sorted().toList();
    }

    public void validate(List<String> permissions) {
        if (permissions != null && permissions.stream().anyMatch(p ->
                p == null || !PermissionCatalog.CODES.contains(p))) {
            throw new BizException(ErrorCode.PARAM_INVALID, "未知功能权限码");
        }
    }

    @Transactional
    public void replace(String roleCode, List<String> permissions, String operator) {
        validate(permissions);
        mapper.delete(new LambdaQueryWrapper<SecRolePermission>()
                .eq(SecRolePermission::getRoleCode, roleCode));
        for (String permission : permissions.stream().distinct().toList()) {
            SecRolePermission row = new SecRolePermission();
            row.setRoleCode(roleCode);
            row.setPermissionCode(permission);
            row.setCreatedAt(LocalDateTime.now());
            row.setCreatedBy(operator);
            mapper.insert(row);
        }
    }
}
