package org.example.seedancegenarate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.example.seedancegenarate.entity.CanvasNode;

import java.util.List;

@Mapper
public interface CanvasNodeMapper extends BaseMapper<CanvasNode> {

    /** 原子开始新运行代际：换 requestId 的同时清空旧 task/output，防旧关联冒充本轮成功。 */
    @Update("UPDATE canvas_node SET submit_request_id = #{requestId}, status = 'PENDING', "
            + "task_id = NULL, output = NULL, error_msg = NULL "
            + "WHERE id = #{id} AND status = #{expectedStatus}")
    int beginRun(@Param("id") Long id,
                 @Param("expectedStatus") String expectedStatus,
                 @Param("requestId") String requestId);

    /**
     * 提交前原子占位：PENDING / FAILED / BLOCKED -> PROCESSING。
     * 返回 0 表示另一个 Worker 已经占到（或节点已完成），调用方直接放弃本次提交，
     * 这是防「同一节点被并发双提交、白扣两次钱」的那道闸。
     * <p>
     * 顺带清 error_msg：作业重试是从 FAILED 重新占位进来的，不清就会出现
     * 「节点正在生成中，却挂着上一次的失败原因」——前端状态行按 errorMsg 优先显示，用户会以为又炸了。
     */
    @Update("UPDATE canvas_node SET status = 'PROCESSING', error_msg = NULL WHERE id = #{id} "
            + "AND submit_request_id = #{requestId} AND status IN ('PENDING', 'FAILED', 'BLOCKED')")
    int occupyForSubmit(@Param("id") Long id, @Param("requestId") String requestId);

    /** 只有仍属本代际的 PROCESSING 节点可被提交前校验判失败。 */
    @Update("UPDATE canvas_node SET status = 'FAILED', error_msg = #{error} WHERE id = #{id} "
            + "AND status = 'PROCESSING' AND submit_request_id = #{requestId}")
    int failIfCurrent(@Param("id") Long id,
                      @Param("requestId") String requestId,
                      @Param("error") String error);

    /**
     * Worker 已创建生成任务、但在回写节点前中断时，按本轮持久化请求号补回关联。
     * submit_request_id 是运行代际栅栏：节点若已开始新一轮，旧 Worker 不得串回旧任务。
     */
    @Update("UPDATE canvas_node SET task_id = #{taskId} WHERE id = #{id} "
            + "AND status = 'PROCESSING' AND submit_request_id = #{requestId} "
            + "AND (task_id IS NULL OR task_id = '')")
    int linkTaskIfMissing(@Param("id") Long id,
                          @Param("requestId") String requestId,
                          @Param("taskId") String taskId);

    /** 远端终态事件只允许回填仍绑定该 taskId 的 PROCESSING 代际。 */
    @Update("UPDATE canvas_node SET status = #{status}, error_msg = #{errorMsg}, "
            + "output = CASE WHEN #{output} IS NULL THEN output ELSE #{output} END "
            + "WHERE id = #{id} AND status = 'PROCESSING' AND task_id = #{taskId}")
    int finishTaskIfCurrent(@Param("id") Long id,
                            @Param("taskId") String taskId,
                            @Param("status") String status,
                            @Param("output") String output,
                            @Param("errorMsg") String errorMsg);

    /**
     * 恢复查询暂未看到任务时，只释放当前运行代际的空关联节点。
     * requestId + PROCESSING + 空 taskId 共同构成栅栏，迟到 Worker 不得覆盖新一轮或已补链状态。
     */
    @Update("UPDATE canvas_node SET status = 'FAILED', error_msg = #{error} WHERE id = #{id} "
            + "AND status = 'PROCESSING' AND submit_request_id = #{requestId} "
            + "AND (task_id IS NULL OR task_id = '')")
    int releaseForRetryIfUnlinked(@Param("id") Long id,
                                  @Param("requestId") String requestId,
                                  @Param("error") String error);

    /**
     * 对账入口：有节点还在跑或还没跑的画布。
     * <p>
     * 按节点状态找而不是按 canvas.status='RUNNING' 找 —— 画布状态是<b>推导</b>出来的，
     * 恰恰会被卡住的节点算错（节点卡 PROCESSING、画布却已写成 DONE），
     * 用它来筛就会漏掉最需要修的那一块。
     */
    @Select("SELECT DISTINCT canvas_id FROM canvas_node WHERE status IN ('PENDING', 'PROCESSING')")
    List<Long> selectCanvasIdsWithActiveNodes();
}
