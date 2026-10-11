package com.hrchat.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hrchat.chat.entity.ChtAnalysisTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface ChtAnalysisTaskMapper extends BaseMapper<ChtAnalysisTask> {

    /** 条件落终态：仅当行仍执行中时写入，避免中断/取消后的迟到结果覆盖当前状态。 */
    @Update("UPDATE cht_analysis_task SET status = #{status}, result_json = #{resultJson}, "
            + "finished_at = #{finishedAt} WHERE task_id = #{taskId} AND status = 'RUNNING'")
    int finish(@Param("taskId") String taskId, @Param("status") String status,
               @Param("resultJson") String resultJson, @Param("finishedAt") LocalDateTime finishedAt);

    /** 重启处置：残留未完成尝试标中断，不承诺自动恢复执行。 */
    @Update("UPDATE cht_analysis_task SET status = 'INTERRUPTED', finished_at = CURRENT_TIMESTAMP "
            + "WHERE status = 'RUNNING'")
    int interruptRunning();
}
