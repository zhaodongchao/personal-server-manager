package com.serverpanel.system.security;

import cn.dev33.satoken.stp.StpInterface;
import com.serverpanel.system.service.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Sa-Token 权限数据源：@SaCheckPermission / @SaCheckRole 的取数入口。
 */
@Component
@RequiredArgsConstructor
public class StpInterfaceImpl implements StpInterface {

    private final PermissionService permissionService;

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return permissionService.getUserPerms(Long.valueOf(String.valueOf(loginId)));
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        return permissionService.getUserRoleKeys(Long.valueOf(String.valueOf(loginId)));
    }
}
