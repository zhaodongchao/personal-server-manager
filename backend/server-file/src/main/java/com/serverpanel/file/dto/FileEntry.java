package com.serverpanel.file.dto;

/**
 * 文件/目录条目（列表项）。
 */
public record FileEntry(
        String name,
        String path,
        boolean dir,
        long size,
        long lastModified,
        String perms,
        boolean text) {}
