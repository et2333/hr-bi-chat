package com.hrchat.common.api;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 分页结果。
 *
 * @param <T> 行数据类型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "分页结果")
public class PageResult<T> implements Serializable {

    /** 总行数 */
    @Schema(description = "总行数")
    private long total;

    /** 当前页数据 */
    @Schema(description = "当前页数据")
    private List<T> records;

    /** 页码（从 1 起） */
    @Schema(description = "页码")
    private long page;

    /** 每页行数 */
    @Schema(description = "每页行数")
    private long size;

    /**
     * 构造分页结果。
     *
     * @param records 当前页数据
     * @param total   总行数
     * @param page    页码
     * @param size    每页行数
     * @param <T>     行类型
     * @return 分页结果
     */
    public static <T> PageResult<T> of(List<T> records, long total, long page, long size) {
        return new PageResult<>(total, records, page, size);
    }
}
