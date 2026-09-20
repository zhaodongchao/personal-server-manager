package com.serverpanel.common.core;

import java.io.Serial;
import java.io.Serializable;

import lombok.Getter;
import lombok.Setter;

/**
 * 通用分页查询参数。
 *
 * <p>pageSize 做上下限钳制（1 ~ 200），防止恶意大分页拖垮数据库。
 */
@Getter
@Setter
public class PageQuery implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final int MAX_PAGE_SIZE = 200;

    /** 页码，从 1 开始 */
    private long pageNum = 1;

    /** 每页条数，默认 10，最大 200 */
    private long pageSize = 10;

    public long getPageNum() {
        return Math.max(pageNum, 1);
    }

    public long getPageSize() {
        return Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
    }
}
