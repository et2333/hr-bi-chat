package com.hrchat.report.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 报表 XLSX 导出器（阶段3）：第一行标题（报表名+导出时间）、表头行、数据行、
 * 底部水印行「BI智慧助手-仅供内部使用 BR-06 导出人 {empNo}」。
 */
@Slf4j
@Component
public class ReportExcelExporter {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 生成 XLSX 字节。 */
    public byte[] export(String reportName, String empNo, List<String> headers, List<List<String>> rows) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("报表导出");
            Row title = sheet.createRow(0);
            title.createCell(0).setCellValue(safe(reportName) + "  " + LocalDateTime.now().format(TS));
            Row headerRow = sheet.createRow(1);
            for (int i = 0; i < headers.size(); i++) {
                headerRow.createCell(i).setCellValue(headers.get(i));
            }
            int r = 2;
            for (List<String> row : rows) {
                Row dataRow = sheet.createRow(r++);
                for (int i = 0; i < row.size(); i++) {
                    dataRow.createCell(i).setCellValue(safe(row.get(i)));
                }
            }
            Row watermark = sheet.createRow(Math.max(r, 2));
            watermark.createCell(0).setCellValue("BI智慧助手-仅供内部使用 BR-06 导出人 " + safe(empNo));
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("XLSX 导出失败", e);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
