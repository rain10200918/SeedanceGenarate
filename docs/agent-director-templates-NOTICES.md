# 导演模板来源与许可

平台模板位于 `src/main/resources/agent/recipe-templates.json`。这些是按当前有限 Recipe 阶段重新编排的适配内容，不安装、执行或分发外部仓库的运行脚本/SDK，不接管 Approval、钱包、Task 或 Provider。

## 改编来源（固定版本）

| 项目 | Commit | 改编范围 |
| --- | --- | --- |
| https://github.com/xixihhhh/ai-short-drama-skill | 38345d39b27eb877978422bff28b5057e783164f | character-bible、continuity-and-transitions、directing-camera-and-pacing、shot-plan、prompt-compiler、qa-and-repair 中的导演方法 |
| https://github.com/kianaliang-dev/drama-director-skill | 93d3833177d924d45dd3306d7c4d83f53afee24e | SKILL.md 场景组织和九幕策划思路；不沿用 Atlas Cloud/固定15秒/一致性保证 |
| https://github.com/tccnnd/Comic-drama | b6ce9533cd0d76c2786e840b8fbae05d64884fc8 | scripts/prompt_compiler.py 场景、角色不变特征和项目风格分层；不包含 Toonflow 子模块 |
| https://github.com/ChrisChen667788/wind-comic | 6eb0f400abe65dac8cb1b96d65d8b75d9e3d5f8b | lib/director-enhance.ts 镜头结构与导演词汇组织 |

上述来源根许可证均为 MIT，下列版权声明及许可一并保留。

Copyright (c) 2026 Dramake contributors

Copyright (c) 2026 Kiana Liang

Copyright (c) 2026 Comic Drama Workflow contributors

Copyright (c) 2026 ChrisChen667788

MIT License

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## 未导入的来源

- hestudy/ai-short-drama，6c40b883a3489d9e31c0f17cd3e5c02fe66c118e：递归文件树未找到许可证，不复制其内容。
- susirial/purevis_ve_cli，ce6d91dc1b41b2edeaf5163af89cf87642db9459：根目录无许可证；嵌套 libtv-skills-main 的许可不能覆盖自有导演技能，不复制相关内容。

## 平台边界

九个模板只声明已存在的 script-generation、storyboard-generation、prompt-optimization。不会把配音、音乐、合成、视觉质检、联网研究声明成已接通的 Recipe 阶段。分镜产出后通过已有逐幕生成操作进入媒体计划，原费用审批和模型能力校验保留。模板指导不能保证模型输出质量或最终角色一致性；必须检查实际生成素材。

模板从服务端列出，导入为用户私有草稿及已验证规则预览，用户确认后发布不可变版本。编辑草稿后仍需沿现有解析、预览和发布流程。模板升级不更改已发布版本或在跑实例。
