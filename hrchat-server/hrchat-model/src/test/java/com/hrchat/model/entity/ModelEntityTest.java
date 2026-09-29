package com.hrchat.model.entity;

import com.hrchat.model.dto.LlmViews;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** LLM 模块实体/DTO round-trip。 */
class ModelEntityTest {

    @Test
    void llmModelConfigRoundTrip() {
        LlmModelConfig c = new LlmModelConfig();
        c.setId(1L);
        c.setModelCode("qwen-max");
        c.setModelName("通义千问");
        c.setVendor("qwen");
        c.setBaseUrl("https://dashscope.aliyuncs.com");
        c.setApiKey("sk-secret");
        c.setModel("qwen-max-2026");
        c.setTemperature(new BigDecimal("0.2"));
        c.setMaxTokens(4096);
        c.setDeployUrl("http://localhost:8000");
        c.setStatus(1);
        c.setIsDeleted(0);
        c.setCreatedAt(LocalDateTime.of(2026, 9, 28, 10, 0));
        c.setCreatedBy("hr01");
        assertThat(c.getModelCode()).isEqualTo("qwen-max");
        assertThat(c.getTemperature()).isEqualByComparingTo("0.2");
        assertThat(c.getMaxTokens()).isEqualTo(4096);
        assertThat(c.getStatus()).isEqualTo(1);
        assertThat(c.getIsDeleted()).isZero();
        assertThat(c.getCreatedBy()).isEqualTo("hr01");
    }

    @Test
    void llmModelVersionRoundTrip() {
        LlmModelVersion v = new LlmModelVersion();
        v.setId(2L);
        v.setConfigId(1L);
        v.setVersionNo(3);
        v.setConfigJson("{\"model_code\":\"qwen-max\"}");
        v.setApplyResult("SUCCESS");
        v.setAppliedAt(LocalDateTime.of(2026, 9, 28, 11, 0));
        v.setAppliedBy("hr01");
        v.setChangeNote("一键部署");
        assertThat(v.getConfigId()).isEqualTo(1L);
        assertThat(v.getVersionNo()).isEqualTo(3);
        assertThat(v.getApplyResult()).isEqualTo("SUCCESS");
        assertThat(v.getConfigJson()).contains("qwen-max");
        assertThat(v.getAppliedBy()).isEqualTo("hr01");
        assertThat(v.getChangeNote()).isEqualTo("一键部署");
    }

    @Test
    void llmDeployStateRoundTrip() {
        LlmDeployState s = new LlmDeployState();
        s.setId(3L);
        s.setConfigId(1L);
        s.setState("ACTIVE");
        s.setHealthStatus("UP");
        s.setLatencyMs(120);
        s.setLlmProfile("openai");
        s.setLastCheckedAt(LocalDateTime.of(2026, 9, 28, 12, 0));
        s.setUpdatedAt(LocalDateTime.of(2026, 9, 28, 12, 1));
        assertThat(s.getConfigId()).isEqualTo(1L);
        assertThat(s.getState()).isEqualTo("ACTIVE");
        assertThat(s.getHealthStatus()).isEqualTo("UP");
        assertThat(s.getLatencyMs()).isEqualTo(120);
        assertThat(s.getLlmProfile()).isEqualTo("openai");
        assertThat(s.getLastCheckedAt()).isNotNull();
        assertThat(s.getUpdatedAt()).isNotNull();
    }

    @Test
    void modelViewsRecord() {
        LlmViews.ModelView mv = new LlmViews.ModelView(1L, "qwen-max", "通义千问", "qwen", "qwen-max",
                1, "ACTIVE", "UP", 3, LocalDateTime.now());
        assertThat(mv.modelCode()).isEqualTo("qwen-max");
        assertThat(mv.deployState()).isEqualTo("ACTIVE");
        assertThat(mv.healthStatus()).isEqualTo("UP");
        assertThat(mv.versionCount()).isEqualTo(3);

        LlmViews.ModelCreateRequest req = new LlmViews.ModelCreateRequest("qwen-max", "通义千问", "qwen",
                "https://x", "sk-1", "qwen-max", new BigDecimal("0.2"), 4096, null);
        assertThat(req.modelCode()).isEqualTo("qwen-max");
        assertThat(req.temperature()).isEqualByComparingTo("0.2");

        LlmViews.VersionView vv = new LlmViews.VersionView(2L, 3, "{}", "SUCCESS",
                LocalDateTime.now(), "hr01", "一键部署");
        assertThat(vv.versionNo()).isEqualTo(3);
        assertThat(vv.applyResult()).isEqualTo("SUCCESS");

        LlmViews.DeployStateView dv = new LlmViews.DeployStateView(1L, "ACTIVE", "UP", 10, "openai",
                LocalDateTime.now());
        assertThat(dv.state()).isEqualTo("ACTIVE");
        assertThat(dv.healthStatus()).isEqualTo("UP");

        LlmViews.HealthView hv = new LlmViews.HealthView("qwen-max", "ACTIVE", "UP", 10, "openai",
                LocalDateTime.now());
        assertThat(hv.modelCode()).isEqualTo("qwen-max");
        assertThat(hv.healthStatus()).isEqualTo("UP");

        LlmViews.MonitorItemView item = new LlmViews.MonitorItemView(1L, "qwen-max", "通义千问",
                "ACTIVE", "UP", 3, LocalDateTime.now());
        LlmViews.MonitorView mon = new LlmViews.MonitorView(1, 1, 0, 0, List.of(item));
        assertThat(mon.total()).isEqualTo(1);
        assertThat(mon.active()).isEqualTo(1);
        assertThat(mon.items().get(0).versionCount()).isEqualTo(3);

        LlmViews.RollbackRequest rr = new LlmViews.RollbackRequest(5L);
        assertThat(rr.versionId()).isEqualTo(5L);

        LlmViews.ModelDetailView md = new LlmViews.ModelDetailView(1L, "qwen-max", "通义千问", "qwen",
                "https://x", "sk-****", "qwen-max", new BigDecimal("0.2"), 4096, null, 1,
                LocalDateTime.now(), LocalDateTime.now());
        assertThat(md.apiKeyMasked()).isEqualTo("sk-****");
    }
}
