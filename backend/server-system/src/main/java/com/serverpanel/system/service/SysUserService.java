package com.serverpanel.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.serverpanel.common.core.PageQuery;
import com.serverpanel.common.core.PageResult;
import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.framework.security.LoginHelper;
import com.serverpanel.system.dto.UserBody;
import com.serverpanel.system.dto.UserDetailVO;
import com.serverpanel.system.dto.UserVO;
import com.serverpanel.system.entity.SysUser;
import com.serverpanel.system.entity.SysUserRole;
import com.serverpanel.system.mapper.SysUserMapper;
import com.serverpanel.system.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
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
    private final AvatarSupport avatarSupport;

    public PageResult<UserVO> page(PageQuery query, String username, Integer status) {
        // select(...) 显式列举列，刻意排除 avatar（MEDIUMTEXT，最大 1MB base64）与 password：
        // 列表根本不去读大字段；再叠加 UserVO 不含 avatar 字段，形成双重隔离。
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
            .select(SysUser::getId, SysUser::getUsername, SysUser::getNickname,
                SysUser::getGender, SysUser::getEmail, SysUser::getPhone,
                SysUser::getDesc, SysUser::getStatus, SysUser::getLastLoginAt,
                SysUser::getLastLoginIp, SysUser::getCreatedAt, SysUser::getUpdatedAt)
            .like(username != null && !username.isBlank(), SysUser::getUsername, username)
            .eq(status != null, SysUser::getStatus, status)
            .orderByDesc(SysUser::getCreatedAt);
        Page<SysUser> page = userMapper.selectPage(
            new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
        List<UserVO> records = new ArrayList<>(page.getRecords().size());
        for (SysUser item : page.getRecords()) {
            records.add(toVO(item));
        }
        return PageResult.of(records, page.getTotal(),
            query.getPageNum(), query.getPageSize());
    }

    public UserDetailVO get(Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND);
        }
        return toDetailVO(user);
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
        user.setGender(body.getGender() == null ? 0 : body.getGender());
        user.setEmail(body.getEmail());
        user.setPhone(body.getPhone());
        user.setStatus(body.getStatus() == null ? 1 : body.getStatus());
        user.setPassword(passwordEncoder.encode(
            body.getPassword() == null || body.getPassword().isBlank()
                ? "Admin@123" : body.getPassword()));
        // 新增时 avatar 为 null 即「使用默认头像」，与库默认值一致，无需额外处理
        if (body.getAvatar() != null) {
            user.setAvatar(avatarSupport.parseAndValidate(body.getAvatar()));
            user.setAvatarUpdatedAt(LocalDateTime.now());
        }
        userMapper.insert(user);
        bindRoles(user.getId(), body.getRoleIds());
    }

    @Transactional
    public void update(Long id, UserBody body) {
        requireUser(id);
        // 不允许停用当前登录用户
        if (body.getStatus() != null && body.getStatus() == 0
            && id.equals(LoginHelper.getUserId())) {
            throw new ServiceException(ErrorCode.CANNOT_DELETE_SELF);
        }
        // 改用显式 set 更新：updateById 默认 NOT_NULL 策略会忽略 null 字段，
        // 会让「恢复默认头像」静默失效；显式 set 保证头像三态语义可控。
        LambdaUpdateWrapper<SysUser> update = new LambdaUpdateWrapper<SysUser>()
            .eq(SysUser::getId, id)
            .set(SysUser::getNickname, body.getNickname())
            .set(SysUser::getEmail, body.getEmail())
            .set(SysUser::getPhone, body.getPhone());
        if (body.getGender() != null) {
            update.set(SysUser::getGender, body.getGender());
        }
        if (body.getStatus() != null) {
            update.set(SysUser::getStatus, body.getStatus());
        }
        // password 为空表示不改
        if (body.getPassword() != null && !body.getPassword().isBlank()) {
            update.set(SysUser::getPassword, passwordEncoder.encode(body.getPassword()));
        }
        // 头像三态：null=不修改；空串=清除（显式写 NULL）；preset/data=设定
        if (body.getAvatar() != null) {
            update.set(SysUser::getAvatar, avatarSupport.parseAndValidate(body.getAvatar()))
                .set(SysUser::getAvatarUpdatedAt, LocalDateTime.now());
        }
        userMapper.update(null, update);
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

    /** 列表映射：UserVO 不含 avatar，与「查询不取 avatar 列」形成双重隔离 */
    private UserVO toVO(SysUser user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setGender(user.getGender());
        vo.setEmail(user.getEmail());
        vo.setPhone(user.getPhone());
        vo.setStatus(user.getStatus());
        vo.setLastLoginAt(user.getLastLoginAt());
        vo.setLastLoginIp(user.getLastLoginIp());
        vo.setCreatedAt(user.getCreatedAt());
        vo.setUpdatedAt(user.getUpdatedAt());
        return vo;
    }

    /** 详情映射：附加头像原始值与归一化 src，供编辑弹窗回显 */
    private UserDetailVO toDetailVO(SysUser user) {
        UserDetailVO vo = new UserDetailVO();
        BeanUtils.copyProperties(toVO(user), vo);
        vo.setAvatar(user.getAvatar());
        vo.setAvatarUrl(avatarSupport.toRenderable(user.getAvatar()));
        return vo;
    }

    private SysUser requireUser(Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND);
        }
        return user;
    }
}
