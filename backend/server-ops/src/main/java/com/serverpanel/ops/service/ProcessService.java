package com.serverpanel.ops.service;

import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.framework.command.CommandExecutor;
import com.serverpanel.framework.command.ExecResult;
import com.serverpanel.ops.dto.ProcessInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 进程管理：基于 ps / kill，无 root 时展示受限，由面板运行身份决定。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessService {

    private final CommandExecutor commandExecutor;

    /** 列出进程，支持按 名称/pid/用户 关键字过滤 */
    public List<ProcessInfo> list(String keyword) {
        ExecResult result = commandExecutor.exec(
            "ps", "-eo", "pid,user,%cpu,%mem,stat,etime,cmd", "--sort=-%cpu");
        List<ProcessInfo> list = new ArrayList<>();
        for (String line : result.getStdout().split("\\r?\\n")) {
            if (line.isBlank()) {
                continue;
            }
            ProcessInfo info = parseLine(line);
            if (info != null && matches(info, keyword)) {
                list.add(info);
            }
        }
        return list;
    }

    /** 终止进程（SIGKILL） */
    public void kill(long pid) {
        if (pid <= 0) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "无效的进程号");
        }
        ExecResult result = commandExecutor.exec("kill", "-9", String.valueOf(pid));
        if (result.getExitCode() != 0) {
            String msg = result.getStderr().isBlank()
                ? result.getStdout()
                : result.getStderr();
            throw new ServiceException(ErrorCode.PROCESS_NOT_FOUND.getCode(),
                msg.isBlank() ? "进程不存在或无权限" : msg.trim());
        }
    }

    private boolean matches(ProcessInfo info, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String kw = keyword.trim().toLowerCase(Locale.ROOT);
        return String.valueOf(info.getPid()).contains(kw)
            || (info.getUser() != null && info.getUser().toLowerCase(Locale.ROOT).contains(kw))
            || (info.getCmd() != null && info.getCmd().toLowerCase(Locale.ROOT).contains(kw));
    }

    /** 解析 ps 单行；不合法行返回 null */
    private ProcessInfo parseLine(String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 6) {
            return null;
        }
        try {
            ProcessInfo info = new ProcessInfo();
            info.setPid(Long.parseLong(parts[0]));
            info.setUser(parts[1]);
            info.setCpu(Double.parseDouble(parts[2]));
            info.setMem(Double.parseDouble(parts[3]));
            info.setStat(parts[4]);
            info.setElapsed(parts[5]);
            StringBuilder cmd = new StringBuilder();
            for (int i = 6; i < parts.length; i++) {
                if (cmd.length() > 0) {
                    cmd.append(' ');
                }
                cmd.append(parts[i]);
            }
            info.setCmd(cmd.toString());
            return info;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
