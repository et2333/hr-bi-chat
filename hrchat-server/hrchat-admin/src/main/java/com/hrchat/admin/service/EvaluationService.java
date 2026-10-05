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
 * 真实评测 runner 接入前仅管理示例问句，不提供无来源的准确率。</p>
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

    /** 尚未接入真实 runner；拒绝将示例问句伪装成评测结果。 */
    public AdminViews.RunResultView run(String setId) {
        if (!sets.containsKey(setId)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "setId");
        }
        throw new BizException(ErrorCode.PARAM_INVALID, "真实评测尚未接入；当前问题集仅供管理示例问句");
    }

    /** 查询回归结果。 */
    public AdminViews.RunResultView getRun(String runId) {
        throw new BizException(ErrorCode.PARAM_INVALID, "真实评测尚未接入；无可用运行结果");
    }
}
