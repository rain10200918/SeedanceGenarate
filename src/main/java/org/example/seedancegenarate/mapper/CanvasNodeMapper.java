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

    /**
     * 提交前原子占位：PENDING / FAILED / BLOCKED -> PROCESSING。
     * 返回 0 表示另一个 Worker 已经占到（或节点已完成），调用方直接放弃本次提交，
     * 这是防「同一节点被并发双提交、白扣两次钱」的那道闸。
     * <p>
     * 顺带清 error_msg：作业重试是从 FAILED 重新占位进来的，不清就会出现
     * 「节点正在生成中，却挂着上一次的失败原因」——前端状态行按 errorMsg 优先显示，用户会以为又炸了。
     */
    @Update("UPDATE canvas_node SET status = 'PROCESSING', error_msg = NULL WHERE id = #{id} "
            + "AND status IN ('PENDING', 'FAILED', 'BLOCKED')")
    int occupyForSubmit(@Param("id") Long id);

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
