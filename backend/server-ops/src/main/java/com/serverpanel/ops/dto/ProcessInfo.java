package com.serverpanel.ops.dto;

import lombok.Data;

/**
 * 进程信息条目。
 */
@Data
public class ProcessInfo {

    private long pid;

    private String user;

    private double cpu;

    private double mem;

    private String stat;

    /** 已运行时长，如 1-02:03:04 */
    private String elapsed;

    /** 命令行（comm + args） */
    private String cmd;
}
