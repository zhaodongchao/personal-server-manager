package com.serverpanel.ops.service;

import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.framework.command.CommandExecutor;
import com.serverpanel.framework.command.ExecResult;
import com.serverpanel.ops.dto.ServiceInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * systemd 服务管理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServiceService {

    /** 仅允许常规 unit 名，杜绝参数注入与路径穿越 */
    private static final Pattern UNIT_NAME = Pattern.compile("^[a-zA-Z0-9_.@-]+\\.service$");

    private static final Set<String> ACTIONS =
        Set.of("start", "stop", "restart", "reload", "enable", "disable");

    private final CommandExecutor commandExecutor;

    /** 服务列表（活动单元 + 启用状态合并） */
    public List<ServiceInfo> list(String keyword) {
        Map<String, String> enabledMap = loadEnabledStates();
        List<ServiceInfo> list = new ArrayList<>();
        ExecResult result = commandExecutor.exec(
            "systemctl", "list-units", "--type=service", "--all",
            "--no-pager", "--no-legend", "--plain");
        for (String line : result.getStdout().split("\\r?\\n")) {
            if (line.isBlank()) {
                continue;
            }
            ServiceInfo info = parseUnitLine(line);
            if (info == null) {
                continue;
            }
            info.setEnabled(enabledMap.getOrDefault(info.getName(), "-"));
            if (keyword == null || keyword.isBlank()
                || info.getName().toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT))) {
                list.add(info);
            }
        }
        return list;
    }

    /** 服务详情（systemctl status 原始输出） */
    public String detail(String name) {
        validateName(name);
        ExecResult result = commandExecutor.exec(
            "systemctl", "status", name, "--no-pager", "--full");
        return result.getStdout();
    }

    /** 服务操作：start/stop/restart/reload/enable/disable */
    public void action(String name, String action) {
        validateName(name);
        if (!ACTIONS.contains(action)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "不支持的操作: " + action);
        }
        ExecResult result = commandExecutor.exec("systemctl", action, name);
        if (result.getExitCode() != 0) {
            String msg = result.getStderr().isBlank()
                ? result.getStdout()
                : result.getStderr();
            throw new ServiceException(ErrorCode.SERVICE_NOT_FOUND.getCode(),
                msg.isBlank() ? "操作失败" : msg.trim());
        }
    }

    private void validateName(String name) {
        if (name == null || !UNIT_NAME.matcher(name).matches()) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "非法的服务名");
        }
    }

    /** 解析 list-units 行：UNIT LOAD ACTIVE SUB DESCRIPTION */
    private ServiceInfo parseUnitLine(String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 4) {
            return null;
        }
        if (!parts[0].endsWith(".service")) {
            return null;
        }
        ServiceInfo info = new ServiceInfo();
        info.setName(parts[0]);
        info.setLoad(parts[1]);
        info.setActive(parts[2]);
        info.setSub(parts[3]);
        StringBuilder desc = new StringBuilder();
        for (int i = 4; i < parts.length; i++) {
            if (desc.length() > 0) {
                desc.append(' ');
            }
            desc.append(parts[i]);
        }
        info.setDescription(desc.toString());
        return info;
    }

    /** 解析 list-unit-files 得到 unit -> enabled 状态 */
    private Map<String, String> loadEnabledStates() {
        Map<String, String> map = new HashMap<>();
        ExecResult result = commandExecutor.exec(
            "systemctl", "list-unit-files", "--type=service",
            "--no-pager", "--no-legend", "--plain");
        for (String line : result.getStdout().split("\\r?\\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 2 && parts[0].endsWith(".service")) {
                map.put(parts[0], parts[1]);
            }
        }
        return map;
    }
}
