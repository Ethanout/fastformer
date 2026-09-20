# 阶段版本：输入规则修复与对象职责拆分

本版本保存当前工作树中的修复和架构迁移，便于后续回退。它不是 current_todo 的完成版本。

## 本轮范围

- 选区输入队列、草稿与提交归属，以及 Alt 新建、普通点击和成功清理规则。
- 快速起形 Enter 的数据来源、逐点回退和多边面中键规则。用户确认的交互规则已写入会话伪代码。
- 选区、快速起形、Gizmo 和控制点按对象归类，拆出输入分派、选区变换与渲染职责。
- 控制点绘制归入 `client.controlpoint.ControlPointRenderer`，虚线盒绘制归入 `client.render.geometry.DashedBoxRenderer`。绘制顺序、透明度和相机偏移保持原行为。
- 保存已有历史持久化、恢复和预览边界修改。各项范围见对应迁移记录。

## 验证

2026-09-20 执行 `./gradlew test check runGameTestServer` 成功，88 项必需 GameTest 全部通过。日志为 `.dsh-tmp/stage-release-validation.log`。本次单元测试使用最新成功结果的缓存。控制点渲染拆分后的完整 `test check` 已在 `.dsh-tmp/control-point-renderer.log` 中通过。

`git diff --check` 通过。客户端实机、性能与完整故障验收仍未完成。

## 后续

继续处理快速起形固定提交意图、剩余鼠标队列、完整会话生命周期、预览快照和服务端历史所有权。等待计算期间的 Enter 去重、Q 取消未发送意图目前是已确认规范，不能视为客户端实现完成。完整剩余范围保留在 `docs/plan/current_todo.md`。
