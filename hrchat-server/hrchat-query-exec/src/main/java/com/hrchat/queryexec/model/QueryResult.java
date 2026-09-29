package com.hrchat.queryexec.model;

import java.util.List;
import java.util.Map;

/**
 * 查询执行结果（接口文档 ANSWER_DONE.payload.table 对应结构）。
 *
 * @param columns 列元信息（脱敏字段 masked=true，BR-03）
 * @param rows    数据行（≤200 条，明细分页在会话层实现）
 * @param total   总行数（当前页语义；聚合查询为 1）
 */
public record QueryResult(
        List<ColumnMeta> columns,
        List<Map<String, Object>> rows,
        long total) {

    public record ColumnMeta(String key, String name, String type, boolean masked) {
    }
}
