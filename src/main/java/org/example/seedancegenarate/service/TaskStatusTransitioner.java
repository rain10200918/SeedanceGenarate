package org.example.seedancegenarate.service;

import org.example.seedancegenarate.entity.VideoTask;

/**
 * 任务终态唯一写入入口：所有「PROCESSING → FAILED」的迁移（轮询/回调失败、
 * 终态作业转存失败、提交失败、超时/提交断裂兜底）都必须经过这里。
 * <p>
 * 不变量：
 * <ul>
 *   <li>CAS 写入（{@code WHERE status='PROCESSING'}）——已终态的任务不可再被覆盖，
 *       晚到的失败状态不会冲掉成功结果；</li>
 *   <li>幂等——CAS 失败（已终态）静默返回 false，不重复计费/不重复发事件；</li>
 *   <li>副作用统一——只有 CAS 成功才发布 {@link org.example.seedancegenarate.event.TaskStatusChangedEvent}
 *       （SSE 推送前端），不落库不发。</li>
 * </ul>
 * SUCCESS 侧（finalizeTask）已是「CAS + 幂等 + 副作用统一」形态，不重复收口。
 */
public interface TaskStatusTransitioner {

    /** 任务失败（引擎明确失败 / 转存失败等）：CAS PROCESSING→FAILED + error_msg + SSE 通知 */
    boolean markFailed(Long videoTaskId, String message);

    /** 超时 / 提交断裂兜底（对账任务调用），语义同 markFailed，日志标注"超时终止" */
    boolean markTimedOut(Long videoTaskId, String message);

    /** 供读场景使用：返回任务当前状态（null=不存在） */
    String statusOf(Long videoTaskId);

    VideoTask findById(Long videoTaskId);
}
