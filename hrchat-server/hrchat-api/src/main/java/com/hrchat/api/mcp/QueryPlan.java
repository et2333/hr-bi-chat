package com.hrchat.api.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Cross-language QueryPlan v1. No model-owned identity, permissions, SQL or metric versions. */
public record QueryPlan(
        @JsonProperty("schema_version") String schemaVersion,
        String decision,
        @JsonProperty("metric_codes") List<String> metricCodes,
        @JsonProperty("org_scope") OrgScope orgScope,
        @JsonProperty("time_range") TimeRange timeRange,
        @JsonProperty("query_mode") String queryMode,
        @JsonProperty("missing_slots") List<String> missingSlots,
        @JsonProperty("clarification_options") List<ClarificationOption> clarificationOptions,
        @JsonProperty("slot_updates") List<SlotUpdate> slotUpdates,
        @JsonProperty("source_turn_ids") List<String> sourceTurnIds,
        String reason,
        @JsonProperty("decision_summary") String decisionSummary) {
    public record OrgScope(@JsonProperty("org_id") String orgId,
                           @JsonProperty("requested_name") String requestedName,
                           @JsonProperty("include_children") Boolean includeChildren) { }
    public record TimeRange(String start, String end, String grain, String timezone,
                            @JsonProperty("time_type") String timeType) { }
    public record ClarificationOption(@JsonProperty("option_id") String optionId, String label) { }
    public record SlotUpdate(String slot, String operation) { }
}
