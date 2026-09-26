package com.serverpanel.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.serverpanel.system.entity.SysUserOauth;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户第三方登录绑定 Mapper。
 *
 * @author zhaodc
 * @since 2026-09-26 UTC+8
 */
@Mapper
public interface SysUserOauthMapper extends BaseMapper<SysUserOauth> {}
