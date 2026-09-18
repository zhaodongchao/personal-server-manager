package com.serverpanel.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.serverpanel.common.core.PageQuery;
import com.serverpanel.common.core.PageResult;
import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.framework.security.LoginHelper;
import com.serverpanel.system.dto.UserBody;
import com.serverpanel.system.entity.SysUser;
import com.serverpanel.system.entity.SysUserRole;
import com.serverpanel.system.mapper.SysUserMapper;
import com.serverpanel.system.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 用户管理 Service。
 */
@Service
@RequiredArgsConstructor
public class SysUserService {

    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final PermissionService permissionService;

    public PageResult<SysUser> page(PageQuery query, String username, Integer status) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
            .like(username != null && !username.isBlank(), SysUser::getUsername, username)
            .eq(status != null, SysUser::getStatus, status)
            .orderByDesc(SysUser::getCreatedAt);
        Page<SysUser> page = userMapper.selectPage(
            new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
        page.getRecords().forEach(u -> u.setPassword(null));
        return PageResult.of(page.getRecords(), page.getTotal(),
            query.getPageNum(), query.getPageSize());
    }

    public SysUser get(Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND);
        }
        user.setPassword(null);
        return user;
    }

    @Transactional
    public void create(UserBody body) {
        if (userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, body.getUsername())) > 0) {
            throw new ServiceException(ErrorCode.USER_EXISTS);
        }
        SysUser user = new SysUser();
        user.setUsername(body.getUsername());
        user.setNickname(body.getNickname());
        user.setEmail(body.getEmail());
        user.setPhone(body.getPhone());
        user.setStatus(body.getStatus() == null ? 1 : body.getStatus());
        user.setPassword(passwordEncoder.encode(
            body.getPassword() == null || body.getPassword().isBlank()
                ? "Admin@123" : body.getPassword()));
        userMapper.insert(user);
        bindRoles(user.getId(), body.getRoleIds());
    }

    @Transactional
    public void update(Long id, UserBody body) {
        SysUser user = requireUser(id);
        // 不允许停用当前登录用户
        if (body.getStatus() != null && body.getStatus() == 0
            && id.equals(LoginHelper.getUserId())) {
            throw new ServiceException(ErrorCode.CANNOT_DELETE_SELF);
        }
        user.setNickname(body.getNickname());
        user.setEmail(body.getEmail());
        user.setPhone(body.getPhone());
        if (body.getStatus() != null) {
            user.setStatus(body.getStatus());
        }
        // password 为空表示不改
        if (body.getPassword() != null && !body.getPassword().isBlank()) {
            user.setPassword(passwordEncoder.encode(body.getPassword()));
        }
        userMapper.updateById(user);
        if (body.getRoleIds() != null) {
            userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getUserId, id));
            bindRoles(id, body.getRoleIds());
        }
    }

    @Transactional
    public void delete(Long id) {
        if (id.equals(LoginHelper.getUserId())) {
            throw new ServiceException(ErrorCode.CANNOT_DELETE_SELF);
        }
        requireUser(id);
        userMapper.deleteById(id);
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>()
            .eq(SysUserRole::getUserId, id));
    }

    /** 用户已绑定的角色 ID 列表 */
    public List<Long> getRoleIds(Long userId) {
        return userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getUserId, userId))
            .stream().map(SysUserRole::getRoleId).toList();
    }

    public void bindRoles(Long userId, List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return;
        }
        for (Long roleId : roleIds) {
            SysUserRole rel = new SysUserRole();
            rel.setUserId(userId);
            rel.setRoleId(roleId);
            userRoleMapper.insert(rel);
        }
    }

    private SysUser requireUser(Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND);
        }
        return user;
    }
}
