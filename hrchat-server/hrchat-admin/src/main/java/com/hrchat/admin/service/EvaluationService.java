package com.hrchat.admin.service;

import com.hrchat.admin.dto.AdminViews;
import com.hrchat.admin.entity.EvlEvalQuestion;
import com.hrchat.admin.mapper.EvlEvalQuestionMapper;
import com.hrchat.common.api.PageResult;
import com.hrchat.common.exception.BizException;
import com.hrchat.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 问句评测（接口文档 2.8，S5 admin-svc）。
 *
 * <p>问题集本地以内存注册表承载（无 evl 集表），问句本体落 evl_eval_question；
 * 回归运行以确定性规则模拟（本地无 LLM 运行时，S7 接入 LangGraph+MockLLM 后替换）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationService {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** 问题集元数据（内存注册表，本地形态）。 */
    private record QuestionSetMeta(String setId, String name, List<Long> questionIds,
                                   LocalDateTime createdAt) {
    }

    private final EvlEvalQuestionMapper questionMapper;
    private final Map<String, QuestionSetMeta> sets = new ConcurrentHashMap<>();
    private final Map<String, AdminViews.RunResultView> runs = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    /** 问题集列表。 */
    public PageResult<AdminViews.QuestionSetView> listQuestionSets(int page, int size) {
        List<AdminViews.QuestionSetView> all = sets.values().stream()
                .sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
                .map(m -> new AdminViews.QuestionSetView(m.setId(), m.name(), m.questionIds().size(),
                        TS.format(m.createdAt())))
                .toList();
        List<AdminViews.QuestionSetView> records = all.stream()
                .skip((long) (page - 1) * size).limit(size).toList();
        return PageResult.of(records, all.size(), page, size);
    }

    /** 创建问题集（问句逐条落库）。 */
    @Transactional
    public String createQuestionSet(AdminViews.QuestionSetCreateRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new BizException(ErrorCode.PARAM_MISSING, "name");
        }
        if (request.questions() == null || request.questions().isEmpty()) {
            throw new BizException(ErrorCode.PARAM_MISSING, "questions");
        }
        List<Long> ids = new ArrayList<>();
        for (AdminViews.QuestionSetCreateRequest.QuestionSpec q : request.questions()) {
            if (q == null || q.question() == null || q.question().isBlank()) {
                continue;
            }
            EvlEvalQuestion row = new EvlEvalQuestion();
            row.setQuestion(q.question().trim());
            row.setSceneTag(q.sceneTag() == null ? "simple" : q.sceneTag());
            row.setExpectJson(q.expectJson() == null ? "{}" : q.expectJson());
            row.setLastResult(null);
            row.setSourceType(1);
            questionMapper.insert(row);
            ids.add(row.getId());
        }
        if (ids.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_MISSING, "questions");
        }
        String setId = "set" + String.format("%03d", seq.incrementAndGet());
        sets.put(setId, new QuestionSetMeta(setId, request.name().trim(), ids, LocalDateTime.now()));
        log.info("评测问题集创建: setId={}, name={}, count={}", setId, request.name(), ids.size());
        return setId;
    }

    /** 运行回归（确定性模拟：约 92% 通过率，失败取序号为 9 的倍数的问句）。 */
    public AdminViews.RunResultView run(String setId) {
        QuestionSetMeta meta = sets.get(setId);
        if (meta == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "setId");
        }
        int passed = 0;
        List<String> failed = new ArrayList<>();
        for (Long id : meta.questionIds()) {
            EvlEvalQuestion q = questionMapper.selectById(id);
            if (q == null) {
                continue;
            }
            boolean pass = id % 10 != 9; // 确定性模拟：90% 通过
            if (pass) {
                passed++;
            } else {
                failed.add(q.getQuestion());
            }
            EvlEvalQuestion update = new EvlEvalQuestion();
            update.setId(id);
            update.setLastResult(pass ? 1 : 0);
            questionMapper.updateById(update);
        }
        int total = meta.questionIds().size();
        double accuracy = total == 0 ? 0.0 : Math.round((double) passed / total * 10000.0) / 100.0;
        AdminViews.RunResultView result = new AdminViews.RunResultView(
                "run" + String.format("%04d", seq.incrementAndGet()), "COMPLETED", accuracy,
                Math.round((accuracy - 2.0) * 100.0) / 100.0, passed, total, failed);
        runs.put(result.runId(), result);
        return result;
    }

    /** 查询回归结果。 */
    public AdminViews.RunResultView getRun(String runId) {
        AdminViews.RunResultView result = runs.get(runId);
        if (result == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "runId");
        }
        return result;
    }
}
