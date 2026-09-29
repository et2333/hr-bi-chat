package com.hrchat.report.service;

import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 报表 PDF 导出器（阶段3，OpenPDF A4）：标题（报表名+导出时间）、表头、数据、
 * 底部水印行「BI智慧助手-仅供内部使用 BR-06 导出人 {empNo}」；中文用 BaseFont STSong-Light。
 */
@Slf4j
@Component
public class ReportPdfExporter {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 生成 PDF 字节。 */
    public byte[] export(String reportName, String empNo, List<String> headers, List<List<String>> rows) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4);
            PdfWriter.getInstance(doc, out);
            doc.open();
            BaseFont bf = cjkFont();
            Font titleFont = new Font(bf, 14, Font.BOLD);
            Font cellFont = new Font(bf, 10);
            doc.add(new Paragraph(safe(reportName) + "  " + LocalDateTime.now().format(TS), titleFont));
            PdfPTable table = new PdfPTable(Math.max(1, headers.size()));
            table.setWidthPercentage(100f);
            for (String header : headers) {
                table.addCell(new PdfPCell(new Phrase(header, cellFont)));
            }
            for (List<String> row : rows) {
                for (String cell : row) {
                    table.addCell(new PdfPCell(new Phrase(safe(cell), cellFont)));
                }
            }
            doc.add(table);
            doc.add(new Paragraph("BI智慧助手-仅供内部使用 BR-06 导出人 " + safe(empNo), new Font(bf, 9)));
            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("PDF 导出失败", e);
        }
    }

    /** 中文 BaseFont：优先 STSong-Light（UniGB-UCS2-H），字体资源缺失时降级 Helvetica。 */
    private BaseFont cjkFont() {
        try {
            return BaseFont.createFont("STSong-Light", "UniGB-UCS2-H", BaseFont.NOT_EMBEDDED);
        } catch (Exception e) {
            log.warn("STSong-Light 字体不可用，降级 Helvetica: {}", e.getMessage());
            try {
                return BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
            } catch (Exception ex) {
                throw new IllegalStateException("PDF 字体初始化失败", ex);
            }
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
