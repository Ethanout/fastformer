# FastFormer 文档

文档只有两个顶层部分：计划和原则。

## 计划

- `plan/future_ideas.md`：未来想法。内容不等于实现授权。
- `plan/feature_todo/`：按日期或版本记录特性、重构和验收范围。
- `plan/current_todo.md`：当前任务的临时清单。完成或取消后删除对应条目。

## 原则

- `principles/design_guidance.md`：设计指导思想和边界。
- `principles/pseudocode/interaction_spec.md`：由用户维护的顶层交互逻辑说明书。
- `principles/pseudocode/sessions/`：四种会话的独立交互说明书。
- `principles/pseudocode/architecture.md`：会话树、客户端、服务端和世界任务的实现映射。
- `principles/interaction_language/interaction_rules.md`：输入归属、选区语义、视觉语言和一致的交互规则。

## 权威规则

当前同步以代码为准。用户下一次修改文档后，修改后的文档成为实现权威版本。

文档不重复保存当前状态。顶层交互语义以 `principles/pseudocode/interaction_spec.md` 为准，代码结构和行为映射以 `principles/pseudocode/architecture.md` 为准，视觉语言以 `principles/interaction_language/interaction_rules.md` 为准。

历史实施计划不再作为当前设计来源。新的特性或重构计划放入 `plan/feature_todo/`，并使用日期或版本命名。
