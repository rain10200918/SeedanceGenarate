package org.example.seedancegenarate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.example.seedancegenarate.entity.PipelineNode;

@Mapper
public interface PipelineNodeMapper extends BaseMapper<PipelineNode> {

    /** 原子开始新运行代际：requestId/status/taskId/error 必须一起切换。 */
    @Update("UPDATE pipeline_node SET submit_request_id = #{requestId}, status = 'PENDING', "
            + "task_id = NULL, video_url = NULL, error_msg = NULL "
            + "WHERE id = #{id} AND status = #{expectedStatus}")
    int beginRun(@Param("id") Long id,
                 @Param("expectedStatus") String expectedStatus,
                 @Param("requestId") String requestId);

    /**
     * 原子占位：PENDING / FAILED → PROCESSING。影响 1 行才允许提交，
     * 防止两个 Worker（或重试与并发 run）同时提交同一节点生成两次。
     */
    @Update("UPDATE pipeline_node SET status = 'PROCESSING', error_msg = NULL "
            + "WHERE id = #{id} AND submit_request_id = #{requestId} "
            + "AND status IN ('PENDING', 'FAILED')")
    int occupyForSubmit(@Param("id") Long id, @Param("requestId") String requestId);

    /**
     * Worker 已创建生成任务、但在回写节点前中断时，按本轮持久化请求号补回关联。
     * submit_request_id 是运行代际栅栏，防止迟到 Worker 覆盖新一轮运行。
     */
    @Update("UPDATE pipeline_node SET task_id = #{taskId} WHERE id = #{id} "
            + "AND status = 'PROCESSING' AND submit_request_id = #{requestId} "
            + "AND (task_id IS NULL OR task_id = '')")
    int linkTaskIfMissing(@Param("id") Long id,
                          @Param("requestId") String requestId,
                          @Param("taskId") String taskId);

    /** 远端终态事件只允许回填仍绑定该 taskId 的 PROCESSING 代际。 */
    @Update("UPDATE pipeline_node SET status = #{status}, error_msg = #{errorMsg}, "
            + "video_url = CASE WHEN #{videoUrl} IS NULL THEN video_url ELSE #{videoUrl} END "
            + "WHERE id = #{id} AND status = 'PROCESSING' AND task_id = #{taskId}")
    int finishTaskIfCurrent(@Param("id") Long id,
                            @Param("taskId") String taskId,
                            @Param("status") String status,
                            @Param("videoUrl") String videoUrl,
                            @Param("errorMsg") String errorMsg);

    /** 只释放当前 requestId 代际中仍未补上 taskId 的节点，供下一租约重新占位。 */
    @Update("UPDATE pipeline_node SET status = 'FAILED', error_msg = #{error} WHERE id = #{id} "
            + "AND status = 'PROCESSING' AND submit_request_id = #{requestId} "
            + "AND (task_id IS NULL OR task_id = '')")
    int releaseForRetryIfUnlinked(@Param("id") Long id,
                                  @Param("requestId") String requestId,
                                  @Param("error") String error);
}
