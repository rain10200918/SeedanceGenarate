package org.example.seedancegenarate.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.entity.Pipeline;
import org.example.seedancegenarate.mapper.PipelineMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动恢复：进程重启会打断流水线后台提交循环（PENDING 节点永远没人提交、状态卡 RUNNING）。
 * 启动时把遗留 RUNNING 状态改为 PARTIAL_FAILED——用户可看到「部分失败」并重试失败节点，
 * 而不是永远卡在「运行中」。已提交的节点（有 taskId）状态由终态事件继续回填，不受影响。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineStartupRecover implements ApplicationRunner {

    private final PipelineMapper pipelineMapper;

    @Override
    public void run(ApplicationArguments args) {
        int updated = pipelineMapper.update(null, Wrappers.<Pipeline>lambdaUpdate()
                .eq(Pipeline::getStatus, "RUNNING")
                .set(Pipeline::getStatus, "PARTIAL_FAILED"));
        if (updated > 0) {
            log.info("启动恢复：{} 条运行中流水线置为 PARTIAL_FAILED", updated);
        }
    }
}
