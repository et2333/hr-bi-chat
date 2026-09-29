package com.hrchat.report.service;

import com.hrchat.authz.model.UserContext;
import com.hrchat.common.api.PageResult;
import com.hrchat.common.exception.BizException;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.report.dto.ReportDtos;
import com.hrchat.report.dto.ReportViews;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ReportTemplateService 补测（FR-13 模板库：列表/详情/实例化）。 */
@ExtendWith(MockitoExtension.class)
class ReportTemplateServiceTest {

    @Mock
    private ReportService reportService;

    private ReportTemplateService service;

    @BeforeEach
    void setUp() {
        service = new ReportTemplateService(reportService);
    }

    @Test
    void list_allWhenCategoryNull() {
        PageResult<ReportViews.TemplateItem> page = service.list(null, 1, 20);
        assertThat(page.getRecords()).hasSize(12);
        assertThat(page.getTotal()).isEqualTo(12);
    }

    @Test
    void list_categoryFiltered() {
        PageResult<ReportViews.TemplateItem> page = service.list("招聘", 1, 20);
        assertThat(page.getRecords()).allMatch(t -> t.category().equals("招聘"));
        assertThat(page.getRecords()).extracting(ReportViews.TemplateItem::id)
                .contains("tpl-recruit", "tpl-new-hires");
    }

    @Test
    void list_blankCategoryTreatedAsAll() {
        PageResult<ReportViews.TemplateItem> page = service.list("  ", 1, 20);
        assertThat(page.getTotal()).isEqualTo(12);
    }

    @Test
    void list_paginationPage2() {
        PageResult<ReportViews.TemplateItem> page = service.list(null, 2, 10);
        assertThat(page.getRecords()).hasSize(2);
    }

    @Test
    void get_found() {
        ReportViews.TemplateDetail detail = service.get("tpl-turnover");
        assertThat(detail.id()).isEqualTo("tpl-turnover");
        assertThat(detail.name()).isEqualTo("离职分析");
        assertThat(detail.paramsSchema()).containsKey("org_id");
    }

    @Test
    void get_notFound_invalid() {
        assertThatThrownBy(() -> service.get("tpl-nope"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    void instantiate_withName_usesTrimmedName() {
        UserContext ctx = UserContext.builder().empNo("hr01").build();
        when(reportService.create(eq(ctx), any(ReportDtos.ReportCreateRequest.class), eq(null)))
                .thenReturn(42L);
        Long id = service.instantiate(ctx, "tpl-monthly-hr", Map.of("org_id", "1"), "  六月月报  ");
        assertThat(id).isEqualTo(42L);
        verify(reportService).create(eq(ctx), any(ReportDtos.ReportCreateRequest.class), eq(null));
    }

    @Test
    void instantiate_nullName_usesTemplateName() {
        UserContext ctx = UserContext.builder().empNo("hr01").build();
        when(reportService.create(eq(ctx), any(ReportDtos.ReportCreateRequest.class), eq(null)))
                .thenReturn(7L);
        Long id = service.instantiate(ctx, "tpl-headcount", null, null);
        assertThat(id).isEqualTo(7L);
    }

    @Test
    void instantiate_nullParams_emptyMap() {
        UserContext ctx = UserContext.builder().empNo("hr01").build();
        when(reportService.create(eq(ctx), any(ReportDtos.ReportCreateRequest.class), eq(null)))
                .thenReturn(1L);
        service.instantiate(ctx, "tpl-turnover", null, "x");
        verify(reportService).create(eq(ctx), any(ReportDtos.ReportCreateRequest.class), eq(null));
    }

    @Test
    void instantiate_unknownTemplate_invalid() {
        UserContext ctx = UserContext.builder().empNo("hr01").build();
        assertThatThrownBy(() -> service.instantiate(ctx, "tpl-nope", Map.of(), "x"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }
}
