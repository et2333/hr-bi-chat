package com.hrchat.queryexec.service.impl;

import com.hrchat.common.exception.BizException;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.queryexec.model.QueryResult;
import com.hrchat.queryexec.service.QueryExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JDBC 只读执行器（local 环境：H2 数据源执行 Doris 风格演示表；prod profile 切换 Doris JDBC）。
 *
 * <p>安全约束（BR-01 只读）：设置 {@code Statement.maxRows} 与超时，禁止执行写语句
 * （JDBC 层不开启 allowMultiQueries，天然拒绝多语句）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JdbcQueryExecutor implements QueryExecutor {

    /** 单次查询最大返回行数（明细分页保护）。 */
    private static final int MAX_ROWS = 200;

    private final DataSource dataSource;

    @Override
    public QueryResult execute(String sql, int timeoutSeconds) {
        long start = System.currentTimeMillis();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setMaxRows(MAX_ROWS);
            ps.setQueryTimeout(timeoutSeconds);
            try (ResultSet rs = ps.executeQuery()) {
                QueryResult result = toResult(rs);
                log.debug("SQL 执行完成: 耗时 {}ms, 列 {} 行 {}", System.currentTimeMillis() - start,
                        result.columns().size(), result.rows().size());
                return result;
            }
        } catch (SQLTimeoutException e) {
            log.warn("SQL 执行超时(>{}s): {}", timeoutSeconds, e.getMessage());
            // 软错误：超时映射 HRD-5001（HTTP 200），提示缩小时间范围
            throw new BizException(ErrorCode.QUERY_TIMEOUT);
        } catch (SQLException e) {
            log.error("SQL 执行失败: {}", e.getMessage());
            // 硬错误：执行失败统一映射 HRS-3001 系统繁忙（500）
            throw new BizException(ErrorCode.SYSTEM_BUSY, e.getMessage());
        }
    }

    private static QueryResult toResult(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();
        List<QueryResult.ColumnMeta> columns = new ArrayList<>(colCount);
        for (int i = 1; i <= colCount; i++) {
            String key = meta.getColumnLabel(i);
            columns.add(new QueryResult.ColumnMeta(key, key, jdbcTypeName(meta.getColumnType(i)), false));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        while (rs.next() && rows.size() < MAX_ROWS) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= colCount; i++) {
                row.put(meta.getColumnLabel(i), normalize(rs.getObject(i)));
            }
            rows.add(row);
        }
        return new QueryResult(columns, rows, rows.size());
    }

    /** 数值类型归一化为 BigDecimal/Number，避免 JSON 序列化溢出。 */
    private static Object normalize(Object value) {
        if (value instanceof java.math.BigInteger bi) {
            return new BigDecimal(bi);
        }
        return value;
    }

    private static String jdbcTypeName(int jdbcType) {
        return switch (jdbcType) {
            case Types.BIGINT, Types.INTEGER, Types.SMALLINT, Types.TINYINT -> "int";
            case Types.DECIMAL, Types.NUMERIC, Types.DOUBLE, Types.FLOAT, Types.REAL -> "number";
            case Types.DATE, Types.TIMESTAMP, Types.TIME -> "datetime";
            default -> "string";
        };
    }
}
