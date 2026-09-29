package com.hrchat.admin.service;

import com.hrchat.admin.dto.AdminViews;
import com.hrchat.admin.entity.EvlEvalQuestion;
import com.hrchat.admin.mapper.EvlEvalQuestionMapper;
import com.hrchat.common.api.PageResult;
import com.hrchat.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 问句评测单测（S5）：问题集创建、回归运行确定性收敛、结果查询。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EvaluationServiceTest {

    @Mock
    private EvlEvalQuestionMapper questionMapper;

    private EvaluationService service;
    private final AtomicLong idSeq = new AtomicLong(100);

    @BeforeEach
    void setUp() {
        service = new EvaluationService(questionMapper);
        when(questionMapper.insert(any())).thenAnswer(inv -> {
            EvlEvalQuestion q = inv.getArgument(0);
            q.setId(idSeq.incrementAndGet());
            return 1;
        });
    }

    @Test
    void createQuestionSet_persistsQuestionsAndLists() {
        List<AdminViews.QuestionSetCreateRequest.QuestionSpec> questions = List.of(
                new AdminViews.QuestionSetCreateRequest.QuestionSpec("本月离职人数", "simple", "{}"),
                new AdminViews.QuestionSetCreateRequest.QuestionSpec("各部门平均薪酬", "complex", "{}"));
        String setId = service.createQuestionSet(
                new AdminViews.QuestionSetCreateRequest("回归集-A", questions));

        assertTrue(setId.startsWith("set"));
        verify(questionMapper, org.mockito.Mockito.times(2)).insert(any());
        PageResult<AdminViews.QuestionSetView> list = service.listQuestionSets(1, 20);
        assertEquals(1, list.getTotal());
        assertEquals(2, list.getRecords().get(0).questionCount());
    }

    @Test
    void createQuestionSet_blankNameThrows() {
        assertThrows(BizException.class, () -> service.createQuestionSet(
                new AdminViews.QuestionSetCreateRequest("  ", List.of())));
    }

    @Test
    void run_regressionDeterministic90Percent() {
        String setId = service.createQuestionSet(new AdminViews.QuestionSetCreateRequest("集",
                java.util.stream.IntStream.rangeClosed(1, 10)
                        .mapToObj(i -> new AdminViews.QuestionSetCreateRequest.QuestionSpec(
                                "问句" + i, "simple", "{}"))
                        .toList()));
        when(questionMapper.selectById(any())).thenAnswer(inv -> {
            EvlEvalQuestion q = new EvlEvalQuestion();
            q.setId((Long) inv.getArgument(0));
            q.setQuestion("问句" + ((Long) inv.getArgument(0) - 100));
            return q;
        });

        AdminViews.RunResultView result = service.run(setId);

        assertEquals("COMPLETED", result.status());
        assertEquals(10, result.total());
        assertEquals(9, result.passed());
        assertEquals(90.0, result.accuracy());
        assertEquals(1, result.failedQuestions().size());
    }

    @Test
    void getRun_unknownRunThrows() {
        assertThrows(BizException.class, () -> service.getRun("run-unknown"));
    }
}
