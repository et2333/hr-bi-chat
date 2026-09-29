package com.hrchat.report.dto;

import java.util.List;
import java.util.Map;

/**
 * 报表请求体 DTO 聚合（接口文档 2.3）。
 */
public final class ReportDtos {

    private ReportDtos() {
    }

    /** 刷新配置。 */
    public record Refresh(String frequency, String time) {
    }

    /** 组件规格（def 为语义层对象 {metric,dim,filter}，非 SQL）。 */
    public record ComponentSpec(String compType, String chartType, Map<String, Object> def) {
    }

    /** 创建报表请求（source_type：ASK/TEMPLATE/CUSTOM）。 */
    public record ReportCreateRequest(String sourceType, String sourceId, String name, String folder,
                                      Refresh refresh, List<ComponentSpec> components,
                                      Map<String, Object> params) {
    }

    /** 更新报表请求（所有者）。 */
    public record ReportPatchRequest(String name, String folder, Refresh refresh,
                                     List<ComponentSpec> components) {
    }

    /** 订阅收件人。 */
    public record Receiver(String type, String id) {
    }

    /** 创建订阅请求（FR-14）。 */
    public record SubscribeRequest(String frequency, String channel, List<Receiver> receivers,
                                   String pushTime, Integer dayOfMonth) {
    }

    /** 导出请求（FR-19/BR-06）。 */
    public record ExportCreateRequest(String format, Map<String, Object> filters) {
    }
}
