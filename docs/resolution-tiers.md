# 清晰度档位：开发与验收说明

## 用户看到什么

统一命名480P、720P、1080P、2K、4K，界面只显示当前模型支持的子集。
2K在本产品中指约2560×1440级别；档位按16:9输出像素量归类，其他比例近似保持像素量，不承诺短边恒等于档位数字。
提示文案：实际尺寸随模型和画面比例变化，部分模型通过超分输出。
超分选项带“超分”标记，单项规格只读。4K保留名称，但当前核对的模型没有足够输出能力，不下发4K选项。

## 能力由谁定义

现有ModelSpec增加resolutions和defaultResolution，工作流builder在声明模型能力时显式配置，前端不得根据名称包含HD或根据MP自行推断。
工作流JSON、实际节点参数注入范围和计费费率不因此修改。

| 模型 | 480P输入MP | 720P输入MP | 1080P输入MP | 2K输入MP | 输出方式 |
| --- | --- | --- | --- | --- | --- |
| minimax-h3-opt / accel / 4step | 0.4 | 0.9 | 2.0 | 不支持 | 不额外超分 |
| minimax-h3-hd / t2v-hd / fl2va-hd | 不支持 | 0.2 | 0.5 | 0.9 | 宽高各×2，再二次采样 |
| minimaxh3-hd-fast | 固定0.5，不传MP | 不支持 | 不支持 | 不支持 | 输出链未连接超分节点 |
| minimax-h3 / minimax-h3-t2v | 固定规格，不传MP | 不支持 | 不支持 | 不支持 | 模板固定生成规格 |
| flux2-image-edit | 0.5 | 1.0 | 2.0 | 不支持 | 按已有图像像素量参数 |

表中均为近似产品档位，输入MP不是最终成品MP。首尾帧流程的实际画幅还受输入图像影响。
未核对输出尺寸的固定模型不虚构清晰度，沿用模型默认。新增模型需核对实际输出依赖链后声明能力，不能只看节点标题或模型名称。

## 前后端契约

模型能力示例：

```json
{
  "resolutions": [
    {"id":"720p","megapixels":0.2,"upscaled":true},
    {"id":"1080p","megapixels":0.5,"upscaled":true},
    {"id":"2k","megapixels":0.9,"upscaled":true}
  ],
  "defaultResolution":"720p"
}
```

新请求优先提交resolution，id区分大小写。旧megapixels继续支持；同时提交时必须与当前档位映射匹配，否则新请求400。
固定模型的能力项megapixels为null：解析后不注入MP，沿用原工作流固定参数。
默认档位只供新UI初始化，不能改变未提供新字段的旧请求默认行为。

| 入口 | 新字段位置 |
| --- | --- |
| POST /api/video/image2video | multipart表单resolution |
| POST /api/video/text2video | JSON顶层resolution |
| GET /api/video/estimate | query resolution；可校验旧megapixels |
| Chat发送 | generation.resolution |
| Agent DIRECT准备 | input.resolution |
| 公开v1视频提交和报价 | JSON顶层resolution |

估价返回解析后的resolution、megapixels，费用仍按原模型/时长规则计算，不新增分辨率附加费。
各能力入口应同源提供新字段；模型列表缓存须避免旧响应长时间遮蔽新能力。

## 历史与重试

- 实际任务继续保存解析后的MP；不新增数据库字段，不用当前模型能力倒推历史成品清晰度。
- Agent DIRECT清洗后将实际MP写入审批snapshot，resolution不进入既有自主审批合同或历史grant hash。
- 旧偏好/重做的MP不能确定等价档位时，提示重新选择；不静默替换后立即付费生成。
- 切模型后不支持原档位时提示用户调整，报价旧响应不得覆盖新选择。
- 已受理请求沿既有幂等规则重放，不用新能力重新解释旧任务。
- 能力缺失的旧后端不猜档位；合法遗留MP可明确展示原规格，无参数的模型仍走原默认路径。

## 验收边界

单元/控制器测试检查合法映射、不支持的4K、MP冲突、固定模型、报价/生成一致和兼容旧请求。
前端测试覆盖三个选择入口、旧偏好、模型切换、JSON/multipart传参、固定只读及能力缺失。
浏览器测试全部使用模拟接口，检查真实Vue组件和桌面/手机明暗截图。不能用真实支付或GPU生成作为默认测试步骤。
本文件描述实现合同；具体已通过的测试与未验证项见 .my-loop/CURRENT-resolution-tiers.md，源码能力与实际成品验证是不同证据层级。
