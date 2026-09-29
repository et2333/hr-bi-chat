package com.hrchat.queryexec.service.impl;

import com.hrchat.common.error.ErrorCode;
import com.hrchat.common.exception.BizException;
import com.hrchat.queryexec.model.QueryResult;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Types;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * JdbcQueryExecutor 单测：真实 H2 内存库验证列/行映射与行数上限，Mockito 验证超时与失败映射。
 */
class JdbcQueryExecutorTest {

    private JdbcDataSource ds;
    private JdbcQueryExecutor executor;

    @BeforeEach
    void setUp() throws SQLException {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:qexec_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        try (Connection conn = ds.getConnection();
             var st = conn.createStatement()) {
            st.execute("CREATE TABLE emp (id BIGINT, name VARCHAR(50), salary DECIMAL(12,2), hired DATE)");
            st.execute("INSERT INTO emp VALUES (1, '张雨晴', 15000.50, '2021-03-01')");
            st.execute("INSERT INTO emp VALUES (2, '李强', 12000.00, '2022-07-15')");
            st.execute("INSERT INTO emp VALUES (3, '王芳', 9800.00, '2023-11-20')");
        }
        executor = new JdbcQueryExecutor(ds);
    }

    @Test
    void execute_returnsColumnsAndRows() {
        QueryResult r = executor.execute("SELECT id, name, salary, hired FROM emp ORDER BY id", 5);
        assertEquals(4, r.columns().size());
        assertEquals(List.of("int", "string", "number", "datetime"),
                r.columns().stream().map(QueryResult.ColumnMeta::type).toList());
        assertEquals(3, r.rows().size());
        assertEquals(3, r.total());
        Map<String, Object> first = r.rows().get(0);
        assertEquals("张雨晴", first.get("NAME"));
        assertEquals(new BigDecimal("15000.50"), first.get("SALARY"));
    }

    @Test
    void execute_capsRowsAtMax() {
        try (Connection conn = ds.getConnection();
             var st = conn.createStatement()) {
            for (int i = 4; i <= 250; i++) {
                st.execute("INSERT INTO emp VALUES (" + i + ", 'u', 1000.00, '2024-01-01')");
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        QueryResult r = executor.execute("SELECT id FROM emp", 5);
        assertEquals(200, r.rows().size());
    }

    @Test
    void execute_timeout_mapsToQueryTimeout() throws Exception {
        DataSource mockDs = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(mockDs.getConnection()).thenReturn(conn);
        when(conn.prepareStatement("SELECT 1")).thenReturn(ps);
        when(ps.executeQuery()).thenThrow(new SQLTimeoutException("timeout"));
        JdbcQueryExecutor ex = new JdbcQueryExecutor(mockDs);
        BizException e = assertThrows(BizException.class, () -> ex.execute("SELECT 1", 1));
        assertEquals(ErrorCode.QUERY_TIMEOUT, e.getErrorCode());
    }

    @Test
    void execute_sqlFailure_mapsToSystemBusy() throws Exception {
        DataSource mockDs = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(mockDs.getConnection()).thenReturn(conn);
        when(conn.prepareStatement("SELECT 1")).thenReturn(ps);
        when(ps.executeQuery()).thenThrow(new SQLException("table not found"));
        JdbcQueryExecutor ex = new JdbcQueryExecutor(mockDs);
        BizException e = assertThrows(BizException.class, () -> ex.execute("SELECT 1", 5));
        assertEquals(ErrorCode.SYSTEM_BUSY, e.getErrorCode());
    }

    @Test
    void execute_bigInteger_normalizedToBigDecimal() throws Exception {
        DataSource mockDs = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        when(mockDs.getConnection()).thenReturn(conn);
        when(conn.prepareStatement("SELECT 1")).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnLabel(1)).thenReturn("big");
        when(meta.getColumnType(1)).thenReturn(Types.BIGINT);
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn(new java.math.BigInteger("9223372036854775808"));
        JdbcQueryExecutor ex = new JdbcQueryExecutor(mockDs);
        QueryResult r = ex.execute("SELECT 1", 5);
        assertEquals(new BigDecimal("9223372036854775808"), r.rows().get(0).get("big"));
    }
}
