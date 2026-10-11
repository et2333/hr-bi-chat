package com.hrchat.report.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hrchat.report.entity.RptInsightTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface RptInsightTaskMapper extends BaseMapper<RptInsightTask> {

    /** 认领待执行任务：仅 PENDING→RUNNING 成功一次，并递增 attempt。 */
    @Update("UPDATE rpt_insight_task SET status = 'RUNNING', attempt = attempt + 1, "
            + "updated_at = CURRENT_TIMESTAMP WHERE id = #{id} AND status = 'PENDING'")
    int claim(@Param("id") Long id);

    /**
     * 条件落库：仅当任务仍处于执行中且 attempt 未被重试/取消推进时写入，
     * 保证取消、过期或被新 attempt 取代的迟到结果不会覆盖当前结果。
     */
    @Update("UPDATE rpt_insight_task SET status = #{status}, result_json = #{resultJson}, "
            + "generation_json = #{generationJson}, degradation_reason = #{degradationReason}, "
            + "failure_reason = #{failureReason}, insight_generated_at = #{generatedAt}, "
            + "updated_at = CURRENT_TIMESTAMP WHERE id = #{id} AND attempt = #{attempt} AND status = 'RUNNING'")
    int finish(@Param("id") Long id, @Param("attempt") Integer attempt, @Param("status") String status,
               @Param("resultJson") String resultJson, @Param("generationJson") String generationJson,
               @Param("degradationReason") String degradationReason, @Param("failureReason") String failureReason,
               @Param("generatedAt") LocalDateTime generatedAt);
}
