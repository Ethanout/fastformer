# FastFormer 文档

文档包含计划、原则、变更记录和历史存储说明。

## 计划

- `plan/future_ideas.md`：未来想法。内容不等于实现授权。
- `plan/feature_todo/`：按日期或版本记录特性、重构和验收范围。
- `plan/current_todo.md`：当前任务的临时清单。完成或取消后删除对应条目。
- `plan/design_questions.md`：需要核对的设计问题与暂定方案。根目录不再另设问题清单。
- `plan/proposals/`：尚未实施的设计草案。

## 原则

- `principles/design_guidance.md`：设计指导思想和边界。
- `principles/pseudocode/sessions/README.md`：顶层会话树与共同规则。
- `principles/pseudocode/sessions/`：五种会话的交互说明书及实现索引。
- `principles/interaction_language/interaction_rules.md`：输入归属、选区语义、视觉语言和一致的交互规则。

## 其他记录

- `changelog/`：按日期保存实施与验证记录。
- `history-storage.md`：世界修改历史的存储说明。
- `source-layout.md`：源码职责、测试目录和依赖检查。

## 权威规则

当前同步以代码为准。用户下一次修改文档后，修改后的文档成为实现权威版本。

顶层交互语义以 `principles/pseudocode/sessions/README.md` 和各会话页为准。代码归属见同目录的实现索引，视觉语言见 `principles/interaction_language/interaction_rules.md`。

历史实施计划不再作为当前设计来源。新的特性或重构计划放入 `plan/feature_todo/`，并使用日期或版本命名。
