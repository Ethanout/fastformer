# 当前 TODO：模块职责重构

基线提交：`562db78`。前轮清单已归档到 [目录整理完成记录](../changelog/2026-09-26-layout-completion.md)。

- [x] MODULE-01：将输入规则、状态、功能事件与会话调度分开，保留内部状态封装。
- [x] MODULE-02：统一历史代码归属，分离恢复日志、内存准入与提交账本。
- [x] MODULE-03：区分几何计算、交互模型和文本，移除工作区模型对网络载荷的依赖。
- [x] MODULE-04：从预览入口提取快速起形缓存和独立绘制职责，减少入口持有的状态。
- [x] MODULE-05：更新文档与架构检查，执行全量单元测试、GameTest 和恢复矩阵。
- [x] MODULE-06：将历史逐方块回放状态机从所有者管理器中分离。

保持 `docs/principles/pseudocode/` 不变。重构以当前行为和存档格式为基准。

## 状态机拆分前的验证结果

- 全量 `clean check runGameTestServer --no-build-cache` 通过。收尾修改后再次执行 `check --no-build-cache`，通过。
- JUnit：311 个测试类，1,836 个测试，无失败或错误，1 个跳过。测试源码与报告逐一匹配，没有遗漏。
- GameTest：118 个必需测试通过。
- 七种恢复场景全部通过：`FirstWrite`、`BeforeHistoryBatch`、`AfterHistoryBatch`、`SealedHistory`、`PublishedHistory`、`MultipleHistory`、`MixedHistory`。
- 包路径与依赖约束、发布 JAR、测试钩子检查和 `git diff --check` 通过。

本轮任务已完成。细节见 [模块重构记录](../changelog/2026-09-26-module-boundaries.md) 和 [源码结构](../source-layout.md)。自动测试没有覆盖游戏内人工视觉验收。

## 重构后的缺陷检查

- [x] BUG-01：解码或服务器投递抛出 `Error` 时释放玩家占用标记与队列名额，并继续传播错误。新增两个回归测试。
- [x] BUG-02：移除仅为旧测试保留的空历史协调构造器，生命周期测试改用真实协调接口。
- `check` 通过：1,838 个测试，无失败或错误，1 个跳过。状态机拆分后的 118 个必需 GameTest 全部通过。
- 状态机拆分后的 `MixedHistory` 独立进程崩溃恢复测试通过。
- [x] BUG-03：服务器停止时重置解码队列，释放旧服务器占用，并隔离旧回调。覆盖相同玩家和传输 ID 的跨服务器回归测试。
- [x] BUG-04：重复分片只占用一次内存预算，避免接近玩家或全局上限时误拒绝上传。新增回归测试。
- 最终 `check --no-build-cache` 通过：1,840 个测试，无失败或错误，1 个跳过。
- 最终代码的 118 个必需 GameTest 全部通过。`git diff --check` 通过。
- 本轮复查了异步解码、服务器退出清理、分片预算和预览缓存失效处理，未再确认需要修复的问题。游戏内人工视觉验收尚未执行。
