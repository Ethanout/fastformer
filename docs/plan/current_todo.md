# 当前 TODO：模块职责重构

基线提交：`562db78`。前轮清单已归档到 [目录整理完成记录](../changelog/2026-09-26-layout-completion.md)。

- [x] MODULE-01：将输入规则、状态、功能事件与会话调度分开，保留内部状态封装。
- [x] MODULE-02：统一历史代码归属，分离恢复日志、内存准入与提交账本。
- [x] MODULE-03：区分几何计算、交互模型和文本，移除工作区模型对网络载荷的依赖。
- [x] MODULE-04：从预览入口提取快速起形缓存和独立绘制职责，减少入口持有的状态。
- [x] MODULE-05：更新文档与架构检查，执行全量单元测试、GameTest 和恢复矩阵。
- [x] MODULE-06：将历史逐方块回放状态机从所有者管理器中分离。

保持 `docs/principles/pseudocode/` 不变。重构以当前行为和存档格式为基准。

## 验证结果

- 全量 `clean check runGameTestServer --no-build-cache` 通过。收尾修改后再次执行 `check --no-build-cache`，通过。
- JUnit：311 个测试类，1,836 个测试，无失败或错误，1 个跳过。测试源码与报告逐一匹配，没有遗漏。
- GameTest：118 个必需测试通过。
- 七种恢复场景全部通过：`FirstWrite`、`BeforeHistoryBatch`、`AfterHistoryBatch`、`SealedHistory`、`PublishedHistory`、`MultipleHistory`、`MixedHistory`。
- 包路径与依赖约束、发布 JAR、测试钩子检查和 `git diff --check` 通过。

本轮任务已完成。细节见 [模块重构记录](../changelog/2026-09-26-module-boundaries.md) 和 [源码结构](../source-layout.md)。自动测试没有覆盖游戏内人工视觉验收。
