package com.hrchat.aiclient.mcp;

import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.semantic.dto.MetricDetail;
import com.hrchat.semantic.service.SemanticMetaService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetSemanticMetaToolHandlerTest {

    @Mock private SemanticMetaService semanticMetaService;
    @Mock private AuthzService authzService;
    @InjectMocks private GetSemanticMetaToolHandler handler;

    @Test
    void metricByName_omitsPhysicalCredentials() {
        when(semanticMetaService.getMetricByCode("headcount")).thenReturn(
                new MetricDetail(1L, "headcount", "在职人数", "STAFF",
                        "SELECT COUNT(1) FROM fact_employee", null, null, 1, 1, 1, 0, 1,
                        List.of("org"), "sys", null));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) handler.call(
                UserContext.builder().empNo("hr01").build(),
                Map.of("type", "METRIC", "names", List.of("headcount")),
                Map.of());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> objects = (List<Map<String, Object>>) result.get("objects");
        assertEquals(1, objects.size());
        assertEquals("headcount", objects.get(0).get("code"));
        assertFalse(objects.get(0).containsKey("jdbc_url"));
        assertFalse(objects.get(0).containsKey("ref_table"));
        verify(authzService).checkFunc(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("chat:ask"));
    }
}
