package com.hrchat.audit.service;

import com.hrchat.audit.model.AuditEvent;

/**
 * 审计收集器：各服务上报审计事件的唯一入口。
 *
 * <p>生产形态经 Kafka {@code hrchat.audit.event} 异步削峰落库（峰值 5000 事件/秒），
 * 本地模块化单体以同步落库实现，保持接口语义一致（BR-11 留痕 ≥3 年）。</p>
 */
public interface AuditCollector {

    /**
     * 记录审计事件。
     *
     * @param event 审计事件
     */
    void record(AuditEvent event);
}
