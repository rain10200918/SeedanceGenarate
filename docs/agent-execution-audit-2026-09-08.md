# Creative Agent 端到端执行审计

审计日期：2026-09-08。对象：当前工作树，而非 Git HEAD 或历史答复。基准任务：“帮我制作一个30秒校园宣传片，电影感，16:9。”不加入 Web Search。

后续实施说明：本报告保留审计时事实。用户随后批准第一切片，文字输出契约/一次修复与片段集Scenario已有变更，最新边界及验收见 [.my-loop/DELIVERY-agent-text-output.md](/Users/a1234/IdeaProjects/SeedanceGenarate/.my-loop/DELIVERY-agent-text-output.md)。下文“未新增测试/普通502”的描述是审计基线，不应当作实施后的全部现状；Turn/Plan通用解耦和合成仍未实施。

## 1. 结论：Level B

**核心方向正确，但 Runtime 契约、状态所有权和跨模块输出协议需要重构。不是继续修单个 Prompt，也没有证据要求推倒现有架构。**

当前已经是有持久状态、异步等待、审批、批次 Barrier、自动续跑、Turn Yield 的 Agent 执行系统；不是单次 LLM 接口。但是，它更擅长在受控顺序计划里选能力、补参数，尚不具备稳定的跨步骤故障解决和完整成片交付能力。

频繁失败有共同来源：

1. Planner 的输出适配逐渐完善了，文字 Skill 仍分别解析自由文本 JSON；同样的模型输出在不同路径有不同结果。
2. 失败恢复按异常类不断加分支，未形成跨 Skill 的共同协议。
3. 通用 Turn 状态写入隐式改变 Plan/Step，普通输出错误因此升级成整个计划失败。
4. 目标规格未完全结构化，后续执行却严格要求模型、比例、时长一致。上游软约束与下游硬校验相冲突。
5. 测试很多，但组件之间的 mock 正好绕开了上述边界；没有完整的创作 Golden Path。

分类：A 有局部解析/诊断问题；B、C、E 是主要问题；D 是生命周期耦合而非模型全部错误；F 需要局部架构收敛，不需要新框架。以下 PASS 表示代码职责及局部证据成立，**不是实测整条生产链路成功**。

## 2. Expected Flow 与 Actual Flow

### Expected

```text
目标 + 30秒/16:9/电影感
 → 持久化目标规格 → Plan
 → Script → Artifact → 自动推进
 → Storyboard + 能力匹配 + 总时长校验 → Artifact
 → 准备全部 Segment 的具体参数和报价
 → Approval（批准已准备的规格，不是批准未来未知任务）
 → 批次 Task → WAITING_TASK
 → 乱序/重复 Task 事件 → 去重 → Artifact → Barrier
 → 全部必要结果就绪
 → 合成（交付要求需要时）→ 文件/时长/数量验收
 → Plan 完成 → 最终摘要（摘要模型失败不撤销交付）
```

用户提出的“批准后再拆 Segment”需明确为：批准前完成实际拆分并冻结规格；批准后才提交。否则无法绑定真实数量和金额。

### Actual

代码路径前缀 `src/main/java/org/example/seedancegenarate/`，下表证据编号在第 14 节可直接打开。

```text
AgentController → AgentApplication.send
 → AgentStore：Session锁、请求去重、Turn、Message、Job
 → AsyncJobWorker → AgentRuntime.execute/begin/current
 → AgentRuntime.context → AgentPlanner → AgentModelGateway
 → LangChain4jPlannerClient → Wire解析/业务校验
 → CALL_SKILL plan-generation
 → PlanGenerationSkill（另一路文本LLM + 手写JSON解析）
 → AgentRuntime.finishSkill → Artifact + AgentPlanStore + continueTurn
 → script-generation → storyboard-generation（各自输出契约）
 → video-generation → quote / 逐幕VideoPromptPreparation检查点
 → AgentBatchRuntime / AgentBatchStore → 持久审批及子项
 → ApprovalApplication / Batch批准 → AGENT_GENERATION Job
 → AgentGenerationRuntime → AgentGenerationGateway
 → VideoSubmitService / Wallet / Task / Provider
 → TaskStatusChangedEvent → onTask → Job（漏事件由reconcile补唤醒）
 → GenerationRuntime.finish → Artifact + PlanStep / Batch.settled
 → continueTurn → 再次Planner
 → COMPLETE → Store.status → Plan SUCCEEDED
```

### 逐节点判定

| 节点 | 判定 | 当前事实与缺口 |
|---|---|---|
| 消息、Session、Turn创建 | PASS | 有数据库、owner检查、client请求去重；运行中普通send被409拒绝，不是通用Replan入口 [E1] |
| Planner结构化Decision | PARTIAL | LC4j协议接入真实存在；不能由此推断作品Skill也结构化 [E5] |
| 创建/采用计划 | PARTIAL | Plan/Step真实参与运行；30秒/16:9主要在自然语言目标中，没有统一硬目标规格 [E6] |
| Script创作保存 | PARTIAL | 实际Skill及Artifact持久化存在；独立解析丢cause，失败恢复不统一 [E6] |
| 文字自动续跑 | PASS | finishSkill记录结果、推进step、入下一Job，不需要用户输入继续 [E2] |
| Storyboard创作 | BROKEN（可靠性契约） | 可成功，不是必失败；但格式/引用/长度等不同错误可全部降成502并终止Plan [E6] |
| 模型能力与分段 | PARTIAL | 验每幕支持时长；默认模型按名称排序，比例取首项，未验总长30秒 [E7] |
| 视频提示词准备 | PARTIAL | 模板、检查点、一次repair已有；自身输出/对白验证仍与其他Skill割裂 [E8] |
| 审批 | PASS（局部验证） | 真正绑定quote，批次绑定hash/总价/项数；不是UI假审批 [E9] |
| 多Task/等待/Barrier | PASS（局部验证） | 有持久子项和Barrier，乱序结果不需Planner判定 [E9/E10] |
| Task结果自动Resume | PASS（局部验证） | 事件门铃+重新读取Task+持久Job+30秒reconcile [E10] |
| 决定Agent下一步的所有权 | WRONG RESPONSIBILITY | GenerationRuntime和BatchStore也决定continue/suspend/complete；Store状态联动隐式驱动Plan [E3/E4/E10] |
| 全部Step完成 | PARTIAL | 防止提前COMPLETE，但成功后还需最后一次Planner；尾部模型失败可反写Plan FAILED [E2/E4] |
| 自动合成、Timeline、成片验收 | MISSING | 当前Agent路径没有VideoMergeSkill/最终文件验收闭环；不能将各段成功称为成片成功 |

**所以“实际能走到哪里”：代码具备走完视频片段批次的路径，但不能证明该自然语言任务能稳定重复到达；即使所有片段成功，仍缺成片级验收和需要时的合成。第一轮不应花GPU验证这些已可静态确认的缺口。**

## 3. 已确认的核心问题与修复方向

### P0：Turn失败隐式成为Plan失败

`AgentStore.status:189`先调用`plans.state`、`recipes.state`再写Turn。`AgentPlanStore.state:157`直接把Turn状态映射到Plan；FAILED还写当前Step FAILED。SUSPENDED则把Step置READY。

因此：分镜输出异常 → Business502 → Runtime.terminalFailure → Turn FAILED → Plan FAILED/Step FAILED。暂停又可能表现成“计划暂停、步骤准备执行”。这里不是多个物理Store同时写数据库的问题，而是**多个业务入口共享了含隐式副作用的通用写状态API**。

推荐拆开执行切片状态与业务事件：`setTurnState`不修改Plan；`StepSucceeded`、`StepNeedsRepair`、`PlanSuspended`显式推进。Step READY只能代表当前可执行，不能同时代表暂停等待处理。现在应改，但必须先确认该语义调整。

### P0：Skill输出契约与恢复不一致

Planner支持整响应围栏去除、optional-null处理；Plan/Script/Storyboard的独立解析未共享这些规则。`StructuredSkillSupport.output:74`严格readTree，`StoryboardGenerationSkill:110`将多类BusinessException覆盖为502，丢失具体字段路径和cause。

例：完整JSON外套围栏、可选字段null、visual超过800字、双份旁白标点不一致、引用无权访问，在业务上不是同一种错误，却可能成为同一个“分镜格式无效”。**不是所有校验过严：引用/归属/审批必须严格；可恢复的表示形式不应直接终止计划。**

推荐轻量`SkillOutputContract<T>`：一个Schema/解析规范化入口、字段路径诊断、有限repair分类。先覆盖Plan/Script/Storyboard/VideoPrompt；保留每个Skill业务校验。仅允许明确的外层格式规范化，不能从任意说明文中猜JSON，不能吞未知字段或忽略引用冲突。旁白收敛到一个canonical字段，旧数据冲突可repair，不能静默选一个。

### P0：恢复策略由异常类名单决定

`Runtime.failed:434`特殊处理LlmChannelException、Search、VideoPreparationException、InvalidAgentDecisionException；其他Business异常走terminalFailure。同为“模型成功返回，但作品结构不合法”，视频可repair、分镜却失败整个Plan。

推荐`FailureDisposition`按阶段、类别、是否已产生外部副作用、次数决定REPAIR/RETRY/SUSPEND/FAIL，而非按每个新Skill扩一个catch。模型文本repair不允许重复创建已批准Task。

### P1：目标、分镜、执行规格未贯通

`PlanGenerationSkill:102`保存自然语言constraints；`StoryboardVideoCapabilities:47`按model名称排序，`:58`比例默认首项，`:66`仅逐幕检查。

用户要30秒16:9，不能依赖模型每一步重传规格。建议持久化最小`targetDurationSeconds / aspectRatio / style`，能力匹配复用现有目录和优先级；分镜总时长、模型实际可生成时长、最终剪辑时长分开。若引擎只能5秒，可规划6段；不能把12秒静默变5秒后仍说原需求完成。

### P1：确定性完成仍依赖尾部LLM

`PlanStore.succeeded:204`完成Step并ready下一步，但没有全部完成时的确定性完成路径。最后再问Planner时失败仍可能把已完成Plan失败。

推荐advance在全部必要结果通过验收后完成Plan；最终聊天摘要作为独立、可失败的展示步骤。缺合成时只能交付“片段素材”，不可用确定性完成掩盖交付缺口。

### P1：受控计划不等于自主故障解决

`PlanStore.validateCall:183`限定currentStep结果类型；不能随意插入研究、补设定等辅助步骤；Recipe派生与局部改幕是专门许可路径。`AgentApplication.send:114`拒绝busy时普通消息。批次终态失败后的普通继续由`BatchStore.beforeStep:136`要求从分镜重新准备未完成幕。

这些限制部分有安全价值；不能通过删除校验获得自主性。后续应允许受控计划修改提案、重新准备/审批，而非LLM自行重购。当前不应宣传任意时刻自然语言Replan。

## 4. 状态与Owner审计

当前状态主要是VARCHAR和字符串分支，**没有一张强制执行的、完整合法迁移表**。下列是实际业务入口可见状态与主路径，不把任意SQL能写入的字符串称为合法状态。

| 对象 | 当前状态/事实 | 事实写入Owner与问题 |
|---|---|---|
| Session | 没有独立status；active_turn_id、revision、goal、workspace及recipe指针 | AgentStore；UI状态由活动执行投影，不能臆造Session枚举 |
| Turn | QUEUED、RUNNING、WAITING_USER、WAITING_SKILL、WAITING_RETRY、WAITING_APPROVAL、SUBMITTING、WAITING_TASK、SUSPENDED、COMPLETED、FAILED、CANCELLED、YIELDED | Store落库；Application/Runtime/GenerationRuntime/Batch等发起改变 |
| Plan | RUNNING、SUCCEEDED、SUSPENDED、FAILED、CANCELLED及由Turn透传的等待/执行态 | PlanStore；state透传甚至可产生WAITING_SKILL/SUBMITTING，是语义泄漏，不是推荐状态表 |
| PlanStep | PENDING、READY、RUNNING、WAITING_USER、WAITING_APPROVAL、WAITING_TASK、WAITING_RETRY、SUCCEEDED、SKIPPED、FAILED、CANCELLED | PlanStore+Scene子状态；受通用Turn迁移驱动 |
| SkillCall | READY、RUNNING、WAITING_RETRY、SUSPENDED、WAITING_APPROVAL、APPROVED、SUBMITTING、WAITING_TASK、SUCCEEDED、FAILED、CANCELLED | Store.callStatus落库；运行器与审批多入口调用 |
| Interaction | PENDING → ANSWERED / EXPIRED | Store与Application.answer；实际叫ANSWERED，不是RESPONDED |
| Approval | PENDING → APPROVED → SUBMITTING → ACCEPTED → SUCCEEDED/FAILED；拒绝/撤销/过期分支 | ApprovalStore；承担授权及提交观察，不能混同普通Interaction |
| Batch | PENDING → RUNNING → SUCCEEDED / PARTIAL_FAILED，另有取消/过期 | BatchStore同时拥有授权检查、dispatch和Barrier推进 |
| VideoTask | 核心PROCESSING → SUCCESS / FAILED；排队/提交/恢复通过phase及attempt细分 | Task Domain；Agent还消费CANCELLED终态视图，不能据此说底层Task所有取消链路都已实现 |

实际主要迁移：

```text
Turn QUEUED → RUNNING
  ASK_USER / 活动Plan内RESPOND → WAITING_USER → 用户响应 → QUEUED(step+1)
  CALL_SKILL → WAITING_SKILL → 成功 → QUEUED(step+1)
    媒体准备完成 → WAITING_APPROVAL → 批准 → SUBMITTING → WAITING_TASK
      Task成功/Barrier通过 → QUEUED(step+1)
      Task失败 → SUSPENDED
  模型暂时错误 → WAITING_RETRY → 同决策重试
  决策修复 → QUEUED(step+1)，次数耗尽 → SUSPENDED
  普通未分类异常 → FAILED（同时Plan/Step失败）
  预算耗尽 → YIELDED + 新QUEUED Turn，原Plan重新绑定
  COMPLETE → COMPLETED（Plan未完守卫仍存在）
  用户停止 → CANCELLED + epoch递增
```

PlanStep正常路径：PENDING→READY→RUNNING→[等待审批/Task/Retry]→SUCCEEDED；前序成功才准备下一步。当前FAILED/SUSPENDED恢复依赖调用方重写状态，不是统一transition守卫；这正是重构对象。

推荐Owner：Resume/Process Manager拥有Agent推进决策，Plan状态机拥有Plan/Step迁移，Approval拥有授权，Task Domain拥有Task。GenerationRuntime只返回媒体观察结果，不决定下一业务步。保持事务内原有锁和幂等，不新增并行消息总线。

## 5. 十项不变量

| 不变量 | 当前结论 | 证据与限制 |
|---|---|---|
| 1 单Session一个有效推进者 | PARTIAL | current锁Session/Turn并验activeTurn/epoch/step，Job续租fence；LLM可在锁外重叠运行，失租不能提交。尚无真实MySQL双worker测试证明 |
| 2 明确可执行Step | PASS（顺序模型） | PlanStore.ready只取首个未成功/跳过步骤；媒体子项由Batch并发，不是任意DAG |
| 3 成功Step不重跑 | PASS（受控路径） | validateCall/currentStep/source守卫；显式改稿会失效下游，是新执行语义 |
| 4 同request只创建一个Task | PARTIAL | 固定requestId、findAccepted、底层提交幂等；局部测试有，未做真实钱包/双实例集成证明 |
| 5 WAITING_TASK不重执行Skill | PASS | Runtime.current不接收该态；GenerationRuntime查询原Task，不重新Planner调用 |
| 6 Required未完成不COMPLETE | PASS | PlanStore.state:160及Runtime COMPLETE校验；反向“全部完成必可交付”缺失 |
| 7 重复Task事件不重复Artifact | PASS（局部验证） | 仅ACCEPTED可终结、source_call_id唯一、Session锁、回调重读Task |
| 8 旧epoch不推进 | PASS（受控路径） | recordMedia先保留作品再验activeTurn/epoch/workspace/currentStep；epoch不是每条用户消息都递增的Session全局版本 |
| 9 Approval绑定实际规格 | PASS（局部验证） | 单项不可变quote+version/epoch/workspace；批次canonical quote hash/binding/金额项数 |
| 10 Turn预算不失败Plan | PASS | yieldToSystemContinue事务创建唯一子Turn、继承Plan/Recipe并入Job；人工次数另有限制 |

以上PASS不意味着可以删除守卫。跨实例隔离、断电事务和未知外部受理仍需真实数据库测试；单元测试不能证明exactly-once外部执行。

## 6. Plan / Turn / Step / Decision 的实际含义

- Plan：持久化整个执行计划，支持系统续跑重新绑定Turn；不是只存在Prompt。
- PlanStep：业务步骤，当前数据库排序字段是`ordinal_no`，另有step_key，不是Runtime日志里的step。
- Turn：有预算的切片；可多次Decision、Skill、Task等待和恢复；不是一次模型调用。
- Turn.step_no：切片内调度/决策槽号。continueTurn递增，决策repair也会占新槽；同一槽可有DECISION和TEXT_SKILL两个Job，因此不是成功业务步骤数。
- Decision.step_no：对应该Turn槽号。失败解析通常无合法Decision记录，诊断/Observation另存。
- Runtime日志step：Turn.step_no，不是第几幕，也不是PlanStep序号。
- Yield后的新Turn.step=0正常；需要结合parentTurnId/turnSeq/Plan ID判断是否错误重启。

概念基本已分开；问题是**状态更新又把不同生命周期绑在一起**，以及Trace缺少统一业务步骤身份。

## 7. Planner Schema是否缺WAIT/APPROVAL

当前允许RESPOND、COMPLETE、ASK_USER、CALL_SKILL。**这本身不是当前缺陷。**

`AgentRuntime.current:166`只让QUEUED/RUNNING/WAITING_RETRY执行Planner。已经WAITING_TASK时应由Runtime阻止模型调用，不需要让模型从四个动作里硬选。若数据库事实已有Task而Turn错误变RUNNING，应修复状态仲裁，不能新增WAIT掩盖。

审批由TaskSkill真实quote准备后创建，不应让LLM编造可收费规格。CALL_SKILL本身带text，能表达“脚本完成，现在生成分镜”。活动计划内RESPOND实际转等待用户，并非总是terminal；可能过度打断，但旧“RESPOND直接结束计划”描述已不准确。当前Wire也已允许非必填字段缺省，旧WIRE_REQUIRED日志不是当前实现证据。

LC4j继续只管模型协议。Plan修改能力未来要增加受控提案/校验/提交，而不是直接把框架Tool callback连到钱包或ComfyUI。

## 8. Resume、Task、Approval真实链路

| 来源 | 当前调用链 | 是否统一推进入口 |
|---|---|---|
| USER_MESSAGE | Application.send → resumeHuman或newTurn → enqueue STEP_JOB | 否；会先处理busy/recipe/batch限制 |
| USER_INTERACTION | Application.answer → Store.answer → resumeHuman → STEP_JOB | 否 |
| APPROVAL | ApprovalApplication.answer / Batch.approve → GENERATION Job | 不再问Planner；这是正确收费边界 |
| TASK_COMPLETED | GenerationRuntime.onTask → wake → Job → finish → continueTurn / Batch.settled | 否；在媒体层决定续跑 |
| RETRY_TIMER | Runtime.recoverModel / Search / Video checkpoint → 延迟Job → begin守卫 | 共用Worker，但策略分散 |
| SYSTEM_CONTINUE | Store.yieldToSystemContinue → 子Turn绑定Plan → STEP_JOB | 专门事务分支 |
| RECOVERY | GenerationRuntime.reconcile + Worker租约恢复 | 同原Job身份恢复，不是从step0重建 |

共享Worker不等于共享业务推进规则。多个专用执行器不是天然错误；需要收敛的是“继续/等待/完成”的决策，而不是把所有代码塞回一个Runtime。

媒体职责追踪：Runtime创建Call；TaskSkill/Gateway准备报价；GenerationRuntime.awaitApproval或BatchStore创建真实批准记录；批准后Gateway.submit走VideoSubmitService创建Task并进入原钱包链；GenerationRuntime.bind置Call/Turn WAITING_TASK；onTask只门铃；finish重读Task，更新Call、保存Artifact；Store.recordMedia/PlanStore更新Step；GenerationRuntime或BatchStore触发续跑。

Approval不是普通Interaction记录。单项审批保存在agent_approval，批次有独立聚合与子项。重复actionId幂等，另一actionId再次批准因status/version被拒绝。已批准quote不能在生成时重算；Plan/workspace变化需要新审批。未知提交结果必须继续核实同requestId，不能自动再买一次。

## 9. 错误分类与处理建议

| 类别 | 现状示例 | 推荐策略 |
|---|---|---|
| PLANNER_PROTOCOL_ERROR | JSON/Wire错误，已有有限修复 | REPAIR有界；耗尽SUSPEND，不失败业务Plan |
| PLANNER_SEMANTIC_ERROR | 提前完成、错Skill/currentStep | 状态可确定则直接推进；否则有界修复/受控REPLAN |
| MODEL_TRANSIENT_ERROR | timeout、429/服务暂忙 | 持久backoff RETRY，同一次模型阶段，不重新媒体提交 |
| MODEL_CONTEXT_ERROR | 上下文超窗 | 有界压缩投影后重试；不能无限提高输出上限 |
| RUNTIME_STATE_ERROR | 不可能状态/绑定不一致 | SUSPEND+诊断；不交给LLM猜状态 |
| SKILL_INPUT_ERROR | 缺来源/非法规格 | ASK_USER或重新准备；引用/权限不可自动放宽 |
| SKILL_EXECUTION_ERROR | 分镜格式/长度/旁白冲突 | 输出契约错误一次REPAIR；不可修复SUSPEND |
| DOMAIN_REJECTED | 余额、权限、价格变化 | ASK_USER/SUSPEND，不能重试扣费或伪装模型故障 |
| INFRASTRUCTURE_TRANSIENT_ERROR | GPU忙、网络短暂失败 | 已创建Task交Task系统重试；未知受理先核实 |
| PERMANENT_CONFIGURATION_ERROR | 模型/工作流不支持 | SUSPEND；重新准备可用规格后重新审批 |
| STALE_EXECUTION | 旧epoch/旧计划结果 | IGNORE推进，按规则保留作品 |

当前确有基础模型细分恢复，不能说所有错误都MODEL_UNAVAILABLE；问题是普通Business400/409/502仍过粗。原始HTTP成功仅证明provider返回，并不证明作品通过契约，Token记录SUCCESS与Skill失败不矛盾。

诊断对象建议记录：category、originStage、retryable、safeOriginalMessage、originalExceptionClass、providerStatus、schemaPath、session/plan/planStep/turn/decision/call/task/job/epoch/attempt。用户文案与诊断分开。脱敏保存cause链，不能为用户友好文案丢掉根因，也不能无条件打印凭据或内部推理。

最近5559字符分镜响应的具体触发字段仍无法确认：没有原文，不能把围栏或null当成已复现的唯一原因。能确认的是它进入普通502终止链；下面测试应补真正原始fixture后锁定个案。

## 10. 本轮测试证据与覆盖缺口

执行现有6组测试：AgentPersistentPlanTest、AgentApprovalIntegrationTest、AgentBatchIntegrationTest、AgentRecipeDerivationTest、AgentVideoPromptPreparationTest、AgentModelRecoveryTest。

首次运行Mockito MockMaker初始化失败，238个Errors；属于测试代理环境。显式加载本地ByteBuddy javaagent后重跑并打开日志确认：

```text
[INFO] Tests run: 238, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time: 13.590 s
[INFO] Finished at: 2026-09-08T14:57:12+08:00
```

原始输出：[成功日志](/tmp/agent-audit-baseline-agent-20260908.log)、[首次环境失败日志](/tmp/agent-audit-baseline-20260908.log)。这些是临时文件，不是永久CI产物。

边界：

- Approval集成测试使用真实H2事务，但mock Planner、TaskSkill.quote、GenerationGateway、AsyncJobService；不能证明MySQL锁竞争/真实钱包。
- Batch乱序Barrier测试走image-generation并预造Storyboard，绕过真实视频准备。
- RecipeDerivation使用真实Script/Storyboard，但mock ModelGateway与媒体执行。
- VideoPromptPreparation有真实模板验证；Runtime checkpoint测试却mock preparation。
- LC4j contract测试有本地HTTP Stub，但不接完整Runtime和媒体链。

**因此238项通过是有用基线，不是完整Golden Path通过。**

### 八条故障测试现状

| 场景 | 现有用例 | 缺口 |
|---|---|---|
| 无效Decision→修复 | PersistentPlan.malformedDecisionProducesBoundedFeedback | 未继续完成整个计划 |
| Timeout→成功 | ModelRecovery.timeoutPersistsDelayAndResumesSameDecision | mock异常，未贯通HTTP映射 |
| 重复TaskCompleted | ApprovalIntegration.lostAcceptanceResponseAndEarlyDuplicateEventRecoverOnce | 要加完整批次双事件与产物/续跑计数 |
| 重复审批 | ApprovalIntegration.explicitApprovalIsDurableScopedAndIdempotent；Batch.immutableQuotesSingleRouteAndIdempotency | 局部覆盖已有 |
| 重复Resume | RuntimeIntegration.skillCreatesArtifactOnceAndAutomaticallyResumes | 顺序重放，不是真实双worker |
| 旧epoch迟到 | SceneIntegration.lateSuccessDoesNotAdvanceOldEpochAndCanBeExplicitlyReused | 取消/局部改幕不等同通用自然语言Replan |
| Yield | PersistentPlan.decisionBudgetYieldsAndReplaysCannotDuplicateContinuation | SQL直接设16触发，非连续达到预算 |
| 永久Skill失败 | PersistentPlan.permanentSkillFailureIsNotBlindlyRetried | 当前断言BUSINESS400/FAILED，固化粗分类，需随契约更新 |

## 11. 新Golden Path验收设计（待架构确认后实施）

本轮先完成Level B审计，不修改生产状态机，也不以假Merge制造“成片通过”。尚未新增完整Scenario Test，这是下一切片的明确交付，不宣称已经完成。

建议两层而不是再堆孤立mock：

1. **状态Scenario**：按用户建议Fake外部Skill/Task，真实Application、Store、Runtime、Plan、Approval、Batch、GenerationRuntime。固定6×5秒，记录一次审批、六个Task、乱序回调、重复投递、Step/Turn状态。
2. **契约贯通Scenario**：把Script/Storyboard/Plan/VideoPreparation替换回真实实现，仅Stub模型HTTP与视频引擎。使用完整角色、镜头、声音、引用fixture，真正跨越当前故障边界。

断言：计划一个；SCRIPT/STORYBOARD各一个；业务媒体步骤一个、子项六个；六个唯一requestId对应六Task；一个批次审批、六子审批；六媒体Artifact且source_call唯一；所有业务Step成功；没有悬挂WAITING_APPROVAL/WAITING_TASK；重放旧Job/审批/结果不增加计数。Plan Artifact是否计入总数在fixture中明确，不能将七八种作品都笼统算“一个结果”。普通ASK_USER Interaction数量与费用Approval分开统计。

注入八条故障，每次仍断言成功Step不重跑、既有Task不重购；额外加入分镜围栏/null/旁白冲突一次repair、所有Step成功后尾部LLM失败、总长不等30秒、busy时改目标的明确产品行为。

当前无合成时，测试终点只能叫“30秒素材片段集交付”，并显式MISSING最终成片。以后合成真实接入后另加文件时长/编码/可读性验收，不能现在用FakeMerge遮蔽缺失。并发另用真实MySQL双worker竞争测试，H2不是锁引擎证明。

## 12. 最小目标架构与迁移

```text
User / Interaction / Approval / Task / Retry / Recovery
 → 复用现有持久Job
 → ResumeCoordinator：claim Session + epoch/version/fence
 → ApplyObservation（去重、保存产物/结果）
 → AdvancePolicy（纯决策）
     WAIT_USER / WAIT_APPROVAL / WAIT_TASK / WAIT_RETRY
     CONTINUE / YIELD / COMPLETE / SUSPEND
 → 需要创作判断时才Planner → LC4j → DecisionValidator
 → SkillExecutor → Domain / Task
```

不新建第二个执行引擎或重复MQ。Coordinator可以先是事务内薄服务；AdvancePolicy不拼Prompt、不调用模型、不处理钱包。已有GenerationRuntime保留Task受理和观察，但逐步停止自行决定Plan走向。

### 切片1：Golden Path与统一输出错误契约

新增上述测试；统一Skill输出诊断、有限repair、cause metadata。覆盖Plan/Script/Storyboard，不改审批/钱包。先固定现状失败回归，再改预期。验收：分镜格式第一次失败第二次成功，旧成功脚本不重跑，Task零重复。

### 切片2：Plan与Turn状态解耦 + advance收敛

拆Store.status隐式联动；统一文字成功、媒体成功、Barrier、重试、Yield后的advance。末尾完成由结果验收决定；总结失败不反写业务失败。验收：全部八类故障+重复恢复+最终摘要模型失败。

### 切片3：创作规格贯通

最小持久目标字段、模型能力/优先级匹配、分镜总时长和比例校验、PreparedSpec来源追踪。旧Plan不猜值，未明确约束需要一次澄清或显式迁移。验收：30秒16:9不被默认模型首比例替换；不支持规格不会带病进入付费任务。

### 切片4：成片交付（独立范围）

如果用户目标要求一个最终视频，再加入Timeline/合成/文件验收。仍经Skill→Domain→Adapter，复用Task与幂等。不能作为本次parser修复的附赠实现。

保持：LC4j、Skill Registry、Artifact版本、Task/Wallet/Worker、批准规格、epoch、数据库事实、检查点。移动：失败策略、推进决策、Plan状态写入。暂不做：任意DAG、多Agent、新框架、全面事件溯源、无审批自动重购。

## 13. 决策与范围

已核对D086（LC4j边界）、D087（续跑/Yield）、D097（批次收费和失败）、D103/D106（逐幕准备/一次repair）。本报告是建议，**未覆盖既有决策**。实施切片2需要明确批准Turn/Plan状态语义调整；切片4需要覆盖原“不合成”范围。不能把历史“Phase3/4”口头编号当作当前代码功能证明。

本轮仅新增此审计文档、运行既有隔离测试。未修改生产代码/Prompt/数据库配置，未访问真实LLM/GPU、未重启服务、未创建收费任务。工作树已有大量用户改动，全部保留。

## 14. 主要代码证据索引

- E1 [AgentApplication.send/answer/busy](/Users/a1234/IdeaProjects/SeedanceGenarate/src/main/java/org/example/seedancegenarate/agent/application/AgentApplication.java:114)
- E2 [AgentRuntime.execute/current/finishDecision/finishSkill/failed](/Users/a1234/IdeaProjects/SeedanceGenarate/src/main/java/org/example/seedancegenarate/agent/runtime/AgentRuntime.java:48)
- E3 [AgentStore.yield/status/continueTurn](/Users/a1234/IdeaProjects/SeedanceGenarate/src/main/java/org/example/seedancegenarate/agent/persistence/AgentStore.java:161)
- E4 [AgentPlanStore.state/validateCall/succeeded](/Users/a1234/IdeaProjects/SeedanceGenarate/src/main/java/org/example/seedancegenarate/agent/persistence/AgentPlanStore.java:157)
- E5 [AgentPlanner](/Users/a1234/IdeaProjects/SeedanceGenarate/src/main/java/org/example/seedancegenarate/agent/model/AgentPlanner.java:49)、[Decision Schema](/Users/a1234/IdeaProjects/SeedanceGenarate/src/main/java/org/example/seedancegenarate/agent/model/AgentDecisionSchema.java:87)、[LC4j Client](/Users/a1234/IdeaProjects/SeedanceGenarate/src/main/java/org/example/seedancegenarate/service/llm/LangChain4jPlannerClient.java:57)
- E6 [StructuredSkillSupport](/Users/a1234/IdeaProjects/SeedanceGenarate/src/main/java/org/example/seedancegenarate/agent/skill/StructuredSkillSupport.java:74)、[StoryboardGenerationSkill](/Users/a1234/IdeaProjects/SeedanceGenarate/src/main/java/org/example/seedancegenarate/agent/skill/StoryboardGenerationSkill.java:71)、[TextSkillSupport](/Users/a1234/IdeaProjects/SeedanceGenarate/src/main/java/org/example/seedancegenarate/agent/skill/TextSkillSupport.java:85)、[PlanGenerationSkill](/Users/a1234/IdeaProjects/SeedanceGenarate/src/main/java/org/example/seedancegenarate/agent/skill/PlanGenerationSkill.java:80)
- E7 [StoryboardVideoCapabilities](/Users/a1234/IdeaProjects/SeedanceGenarate/src/main/java/org/example/seedancegenarate/agent/skill/StoryboardVideoCapabilities.java:24)
- E8 [VideoPromptPreparation](/Users/a1234/IdeaProjects/SeedanceGenarate/src/main/java/org/example/seedancegenarate/agent/generation/AgentVideoPromptPreparation.java:147)
- E9 [ApprovalApplication](/Users/a1234/IdeaProjects/SeedanceGenarate/src/main/java/org/example/seedancegenarate/agent/application/AgentApprovalApplication.java:28)、[BatchStore](/Users/a1234/IdeaProjects/SeedanceGenarate/src/main/java/org/example/seedancegenarate/agent/persistence/AgentBatchStore.java:103)
- E10 [GenerationRuntime](/Users/a1234/IdeaProjects/SeedanceGenarate/src/main/java/org/example/seedancegenarate/agent/runtime/AgentGenerationRuntime.java:141)
- 测试 [ApprovalIntegration](/Users/a1234/IdeaProjects/SeedanceGenarate/src/test/java/org/example/seedancegenarate/agent/AgentApprovalIntegrationTest.java:29)、[PersistentPlan](/Users/a1234/IdeaProjects/SeedanceGenarate/src/test/java/org/example/seedancegenarate/agent/AgentPersistentPlanTest.java:246)、[BatchIntegration](/Users/a1234/IdeaProjects/SeedanceGenarate/src/test/java/org/example/seedancegenarate/agent/AgentBatchIntegrationTest.java:100)
