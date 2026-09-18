package com.serverpanel.framework.command;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 命令执行结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExecResult {

    /** 退出码；超时被杀时为 -1 */
    private int exitCode;

    /** 标准输出（超出上限时截断） */
    private String stdout;

    /** 标准错误 */
    private String stderr;

    /** 是否因超时被终止 */
    private boolean timedOut;

    /** 耗时毫秒 */
    private long durationMs;

    public boolean isSuccess() {
        return exitCode == 0 && !timedOut;
    }
}
