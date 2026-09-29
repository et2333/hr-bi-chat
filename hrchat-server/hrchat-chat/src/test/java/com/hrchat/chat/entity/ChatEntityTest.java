package com.hrchat.chat.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话/问答实体 round-trip 单测（S8c 覆盖率门禁）。
 */
class ChatEntityTest {

    @Test
    void chtFeedbackRoundTrip() {
        ChtFeedback e = new ChtFeedback();
        e.setId(1L);
        e.setTurnId(10L);
        e.setUserId(1L);
        e.setRating(1);
        e.setReason(2);
        e.setComment("很准");
        e.setHandled(0);
        e.setCreatedAt(LocalDateTime.now());
        e.setCreatedBy("hr01");
        e.setUpdatedAt(LocalDateTime.now());
        e.setUpdatedBy("hr01");
        assertThat(e.getTurnId()).isEqualTo(10L);
        assertThat(e.getRating()).isEqualTo(1);
        assertThat(e.getComment()).isEqualTo("很准");
    }

    @Test
    void chtAnswerRoundTrip() {
        ChtAnswer e = new ChtAnswer();
        e.setId(1L);
        e.setTurnId(10L);
        e.setAnswerState(3);
        e.setSummaryText("在职人数 1200");
        e.setResultRef("r-1");
        e.setTotalRows(5);
        e.setChartType("line");
        e.setMetricIds("1,2");
        e.setMetricVersions("1:2");
        e.setDataFreshAt(LocalDateTime.now());
        e.setLatencyMs(120);
        assertThat(e.getAnswerState()).isEqualTo(3);
        assertThat(e.getSummaryText()).isEqualTo("在职人数 1200");
        assertThat(e.getChartType()).isEqualTo("line");
    }

    @Test
    void chtClarifyRoundTrip() {
        ChtClarify e = new ChtClarify();
        e.setId(1L);
        e.setTurnId(10L);
        e.setAmbiguityType(1);
        e.setQuestionText("您想问哪个指标？");
        e.setOptionsJson("[{\"code\":\"1001\"}]");
        e.setSelectedCode("1001");
        e.setSavedAsPref(0);
        assertThat(e.getAmbiguityType()).isEqualTo(1);
        assertThat(e.getSelectedCode()).isEqualTo("1001");
    }

    @Test
    void chtTurnRoundTrip() {
        ChtTurn e = new ChtTurn();
        e.setId(10L);
        e.setSessionId(1L);
        e.setTurnSeq(1);
        e.setQuestionText("研发中心在职人数？");
        e.setIntentType(2);
        e.setInheritJson("{}");
        assertThat(e.getSessionId()).isEqualTo(1L);
        assertThat(e.getIntentType()).isEqualTo(2);
    }
}
