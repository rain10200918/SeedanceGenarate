# API Key 月度消费预算

月度预算是单把Key的消费上限，账号钱包仍支付实际费用。预算可以高于当前账号余额，各Key预算之和也可以高于余额；设置预算不会分走或冻结账号资金。新任务必须同时满足账号可用余额和Key剩余额度。

在“我的密钥”和管理员密钥列表点击“月度预算”查看本月上限、已消费、已预占、剩余及下月开始时间。管理员可提高或取消上限；普通属主只能收紧已有上限。null表示不限，0禁止新的付费提交，免费任务和已有任务查询不受消费预算拦截。降低至已消费/预占以下时剩余显示0，已受理任务照常完成。当前策略延用至以后月份。

## API

- GET/PATCH `/api/api-keys/{id}/budget`：登录属主；他人Key返回404。
- GET/PATCH `/api/admin/api-keys/{id}/budget`：管理员。
- GET `/api/v1/account/key-budget`：当前API凭证的预算，只读。

PATCH的两个字段都必须存在：`{"limit":100.00,"expectedVersion":0}`。明确传`limit:null`才表示取消上限；缺字段不是取消指令。金额为0至9999999999.99元，最多两位小数。版本冲突返回409，重新GET后再决定是否保存。

查询和保存返回：`{apiKeyId,limit,consumed,reserved,remaining,period,resetAt,currency,version}`。登录端沿用Result包装；v1直接返回视图。currency固定CNY；period为YYYY-MM；resetAt带+08:00。不限时limit/remaining为null，有限剩余clamp到0。展示数值不是后续受理保证。

提交时预算不足返回403 `API_KEY_SPENDING_LIMIT_EXCEEDED`；账号余额不足沿用402 `INSUFFICIENT_BALANCE`。预算耗尽只阻止新付费任务，不阻止查询和原请求重放；不得以换幂等键自动重投已受理或结果未知的请求。

## 持久状态与分布式边界

V58新增配置、周期汇总、任务授权和授权流水4张表。配置从未设置时为不限；上线前历史任务不虚构预算流水。所有上线后API任务（包括不限Key）记录授权，因此月中设置上限仍能看到本月新消费。历史消费不回填，首月展示仅覆盖新版本受理的任务。

授权记录绑定taskId、keyId、userId、金额和提交月份；`RESERVED`只允许进入`SETTLED`或`RELEASED`。流水主键(task_id,phase)使RESERVE和最终FINISH各只有一笔。周期以数据库UNIX_TIMESTAMP确定的Asia/Shanghai自然月为准；月底提交、下月结束仍计入原月份。零价任务也记录授权，但沿用钱包零价不写流水的行为。

BillingAuthorizationService在可写事务中核对并锁任务行，预算服务锁Key及授权/周期，钱包在同一事务冻结/结算/释放；使用当前读处理旧RR快照。提交保留既有任务身份落库策略，钱包/预算/attempt/job同时提交后才算受理。提交结果未知沿原持久身份追认，不能根据网络异常删任务或退款。成功保留原终态CAS事务；失败保留终态提交后REQUIRES_NEW释放两份资源。数据库不可用时不放行新的付费受理。

API授权有冻结记录时会核对钱包biz_key对应的task/user/type/amount/hold_amount，错误金额及相反终态不会被当作幂等成功。历史无授权任务沿原钱包行为。上游任务身份、租约fencing、超时恢复与UNKNOWN规则继续由既有generation_attempt/async_job处理；账务幂等不等于上游调用恰好一次。

ApiKeyBudgetReconcileTask每轮扫描最多100条RESERVED，按taskId游标推进，循环回头；只对已持久SUCCESS/FAILED协调结算/释放。多个实例可同时扫描，由任务锁、授权状态和唯一流水决定写入，不依赖Redis锁。失败行不堵住后续退款；处理中的任务不会因授权年龄被自动释放。原TaskReconcileTask的钱包缺流水路径也接入相同协调服务。

部署需钱包、预算和作业使用同一个MySQL写库及事务管理器。授权读取不得路由到只读副本。首期按账号钱包行和Key行串行化短事务；热点账号吞吐受钱包锁约束。跨区域多写、分库钱包不在此实现保证范围。

## 上线顺序

先备份数据库并执行V58，再发布全部接收API任务和执行任务的实例，最后开放预算管理入口。旧版本实例没有预算守卫，混合版本期间不能承诺硬限额；需通过负载均衡摘除/排空旧实例完成切换。迁移不设置真实用户预算、不重放任何生成或支付。

测试命令与本轮实测结果见`.my-loop/DELIVERY-api-key-budget.md`。测试通过只代表记录中的环境，不能替代生产切换和主库路由检查。
