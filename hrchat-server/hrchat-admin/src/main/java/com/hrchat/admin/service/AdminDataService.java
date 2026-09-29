package com.hrchat.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.admin.dto.AdminViews;
import com.hrchat.admin.entity.ItgDatasource;
import com.hrchat.admin.entity.ItgSyncTask;
import com.hrchat.admin.mapper.ItgDatasourceMapper;
import com.hrchat.admin.mapper.ItgSyncTaskMapper;
import com.hrchat.audit.model.AuditEvent;
import com.hrchat.audit.model.AuditEvents;
import com.hrchat.audit.service.AuditCollector;
import com.hrchat.authz.model.UserContext;
import com.hrchat.common.api.PageResult;
import com.hrchat.common.exception.BizException;
import com.hrchat.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 数据源与同步监控（接口文档 2.7，S5 admin-svc）。
 *
 * <p>数据质量摘要按 T-1 业务日期聚合；任务重试走人工触发（SYNC_RETRY 记审计）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminDataService {

    public static final String PERM_READ = "admin:data:read";
    public static final String PERM_MANAGE = "admin:data:manage";

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final ItgDatasourceMapper datasourceMapper;
    private final ItgSyncTaskMapper syncTaskMapper;
    private final AuditCollector auditCollector;
    private final ObjectMapper objectMapper;

    /** 数据源列表（含最近一次同步健康）。 */
    public List<AdminViews.DatasourceView> listDatasources() {
        return datasourceMapper.selectList(null).stream().map(this::toDatasourceView).toList();
    }

    /** 同步任务列表（按业务日期/状态过滤）。 */
    public PageResult<AdminViews.SyncJobView> listSyncJobs(LocalDate bizDate, Integer status,
                                                           int page, int size) {
        LambdaQueryWrapper<ItgSyncTask> wrapper = new LambdaQueryWrapper<>();
        if (bizDate != null) {
            wrapper.eq(ItgSyncTask::getBizDate, bizDate);
        }
        if (status != null) {
            wrapper.eq(ItgSyncTask::getExecState, status);
        }
        wrapper.orderByDesc(ItgSyncTask::getBizDate).orderByDesc(ItgSyncTask::getId);
        List<ItgSyncTask> all = syncTaskMapper.selectList(wrapper);
        List<AdminViews.SyncJobView> records = all.stream()
                .skip((long) (page - 1) * size).limit(size)
                .map(this::toSyncJobView).toList();
        return PageResult.of(records, all.size(), page, size);
    }

    /** 手动重试失败任务（exec_state 3→4 重试中）。 */
    @Transactional
    public void retryJob(Long jobId, UserContext ctx) {
        ItgSyncTask task = syncTaskMapper.selectById(jobId);
        if (task == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "jobId");
        }
        task.setExecState(4);
        task.setFailReason(null);
        task.setStartedAt(null);
        syncTaskMapper.updateById(task);
        auditCollector.record(AuditEvent.of(AuditEvents.SYNC_RETRY, ctx.getEmpNo(), "sync_task",
                String.valueOf(jobId), toJson(Map.of("ds_id", task.getDsId(), "retry_by", ctx.getEmpNo())),
                false));
        log.info("同步任务重试: jobId={}, dsId={}, operator={}", jobId, task.getDsId(), ctx.getEmpNo());
    }

    /** 数据质量摘要（缺省取最新业务日期）。 */
    public AdminViews.QualitySummary qualitySummary(LocalDate bizDate) {
        LocalDate date = bizDate != null ? bizDate : latestBizDate();
        if (date == null) {
            return new AdminViews.QualitySummary(null, 0, 0, 0, 0, 0, 0.0);
        }
        List<ItgSyncTask> tasks = syncTaskMapper.selectList(new LambdaQueryWrapper<ItgSyncTask>()
                .eq(ItgSyncTask::getBizDate, date));
        long success = tasks.stream().filter(t -> t.getExecState() == 2).count();
        long failed = tasks.stream().filter(t -> t.getExecState() == 3).count();
        long running = tasks.stream().filter(t -> t.getExecState() == 1 || t.getExecState() == 4).count();
        long delayed = tasks.stream().filter(t -> t.getExecState() == 0).count();
        double completeness = (success + failed) == 0 ? 0.0
                : (double) success / (success + failed);
        return new AdminViews.QualitySummary(date.toString(), tasks.size(), success, failed,
                running, delayed, Math.round(completeness * 10000.0) / 100.0);
    }

    private LocalDate latestBizDate() {
        List<ItgSyncTask> tasks = syncTaskMapper.selectList(new LambdaQueryWrapper<ItgSyncTask>()
                .orderByDesc(ItgSyncTask::getBizDate).last("LIMIT 1"));
        return tasks.isEmpty() ? null : tasks.get(0).getBizDate();
    }

    private AdminViews.DatasourceView toDatasourceView(ItgDatasource ds) {
        List<ItgSyncTask> latest = syncTaskMapper.selectList(new LambdaQueryWrapper<ItgSyncTask>()
                .eq(ItgSyncTask::getDsId, ds.getId()).orderByDesc(ItgSyncTask::getBizDate)
                .orderByDesc(ItgSyncTask::getId).last("LIMIT 1"));
        ItgSyncTask task = latest.isEmpty() ? null : latest.get(0);
        return new AdminViews.DatasourceView(ds.getDsCode(), ds.getDsName(),
                switch (ds.getSyncMode() == null ? 0 : ds.getSyncMode()) {
                    case 1 -> "FULL";
                    case 2 -> "INCREMENT";
                    case 3 -> "CDC";
                    default -> "UNKNOWN";
                }, ds.getStatus(),
                task == null || task.getFinishedAt() == null ? null : TS.format(task.getFinishedAt()),
                task == null ? null : stateName(task.getExecState()),
                task == null ? null : String.valueOf(task.getBizDate()));
    }

    private AdminViews.SyncJobView toSyncJobView(ItgSyncTask task) {
        ItgDatasource ds = datasourceMapper.selectById(task.getDsId());
        return new AdminViews.SyncJobView(task.getId(),
                ds == null ? String.valueOf(task.getDsId()) : ds.getDsCode(),
                ds == null ? "" : ds.getDsName(),
                taskTypeName(task.getTaskType()),
                String.valueOf(task.getBizDate()),
                stateName(task.getExecState()),
                task.getRowsRead(), task.getRowsWritten(), task.getFailReason(),
                task.getStartedAt() == null ? null : TS.format(task.getStartedAt()),
                task.getFinishedAt() == null ? null : TS.format(task.getFinishedAt()));
    }

    static String stateName(Integer state) {
        return switch (state == null ? 0 : state) {
            case 1 -> "RUNNING";
            case 2 -> "SUCCESS";
            case 3 -> "FAILED";
            case 4 -> "RETRYING";
            default -> "PENDING";
        };
    }

    private static String taskTypeName(Integer type) {
        return switch (type == null ? 0 : type) {
            case 2 -> "INCREMENT";
            case 3 -> "DIMENSION";
            default -> "FULL";
        };
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
