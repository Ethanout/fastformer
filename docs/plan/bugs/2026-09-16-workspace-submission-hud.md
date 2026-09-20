# 本地提交等待 HUD

## 问题

客户端发出工作区提交时立刻锁定工作区。锁定让建造与选择动作停止。服务端发布任务活动在之后。网络延迟打开一段窗口：客户端拒绝输入，且没有任何活动行出现。

窗口内 HUD 只做否定式表达。面提示、部件标签、准星操作行都被抑制。玩家看不到等待原因。

纯客户端粘贴提交还会触发第二个问题。`onRenderGui` 的提前返回条件不含本地提交状态，也不含 `controller.active()`。当 `snapshot.enabled` 为假时，整个 HUD 段落根本不执行。

## 实现

新增 `WorkspaceSubmissionHud`，只读普通布尔值，返回本帧要画的内容。该类不依赖客户端实例，测试可以直接调用。

| 方法 | 作用 |
|---|---|
| `hudActive(...)` | 判断 HUD 是否还需绘制 |
| `plan(submissionPending, activityTask, quickReplaceActive, reconnectPending)` | 返回等待键、行位置与提示抑制标志 |
| `waitingLineY(quickReplaceOrTask, reconnectPending)` | 返回第一个未被占用的行 |

修改 `FastPlaceClientPreviewCore`：

1. `onRenderGui` 的门槛加入 `workspaceSubmissionPending`。纯客户端提交现在能画出等待行。
2. 等待行只在 `activity().task()` 为假时画。已有活动行时不再画同义的第二行。
3. 等待期间抑制 `buildingRaycastHint` 与 `buildingBottomHint`。这两处承诺建造动作，被锁定时点击会被拒绝。
4. 保留模式状态行与几何段落的状态文字。这些报告状态，不承诺动作。
5. 填充模式文字在等待时改用 `fill_mode_status`。原文字含方块键，而锁定会阻断该键。
6. 活动行在等待时不再追加取消键提示。取消键在提交期间不发送取消。
7. 准星处的几何悬停文字加锁定守卫。该文字承诺指针动作。

复用现有键 `operation_submit_pending` 与 `fill_mode_status`。未新增语言键。

### 取消键提示为何为假

`WorkspaceSubmissionTracker.observeActivity` 把 `OPERATION_TASK` 设为 `TASK_ACTIVE`。`tick()` 对 `TASK_ACTIVE` 直接返回假。因此跟踪器在整个任务执行期间保持 pending。

`CancelInputSemantics.decide` 在 `submissionPending` 为真时返回 `REPORT_SUBMISSION_PENDING`，不返回 `REQUEST_CANCEL`。所以任务行显示取消键提示时，按该键不会取消。

### 布局

行位置由 `waitingLineY` 计算，不写死常量到调用点。

| 行 | 占用者 |
|---|---|
| y=8 | 填充模式文字 |
| y=20 | Quick Replace 与活动任务行 |
| y=32 | 重连提示 |

等待行从 y=20 起跳过本帧已占用的行：

| Quick Replace 或任务行 | 重连提示 | 等待行 | 说明 |
|---|---|---|---|
| 无 | 无 | y=20 | 直接可用 |
| 有 | 无 | y=32 | 跳过被占的 y=20 |
| 无 | 有 | y=20 | 与重连提示不冲突 |
| 有 | 有 | y=44 | 跳过 y=20 与 y=32 |

## 普通工作区状态行缺口：已修改，待验证

原实现的 `renderSessionHud` 只判断 `PREVIEW_STATE.operation().active()`。该值来自服务端 `OperationPreviewPayload`。

本地工作区可能没有对应的服务端预览。`FastPlaceClientPreviewCore.operationActive()` 同时看三个来源：服务端预览、`ClientOperationController.active()`、`selectionSessionActive()`。底部状态行只看第一个。

结果：纯客户端工作区在工作时，底部不显示选区或操作状态行。

2026-09-16：绘制门槛、底部状态分支与底部偏移判断统一使用 `operationActive()`。本地工作区根据本地选择草稿显示“选区”或“操作”。本地手柄直接提供变换操作，没有独立的服务端阶段模式，因此不再使用服务端模式列表和高亮。没有本地工作区的服务端选区保留原有模式文字。

修改后 `git diff --check` 通过。此前定向测试成功耗时 9 秒，但早于本项修改，不能作为本项通过的证据。修改后的编译与实机视觉验收待执行。

## 验证

新增 `WorkspaceSubmissionHudTest`。状态选择部分覆盖：

- 等待帧显示 `operation_submit_pending`
- 活动行出现时本地等待行停止
- 提交结束时不显示等待行
- 等待期间抑制动作提示，含活动行接管的那一帧
- 纯客户端提交（所有服务端标志为假）时 HUD 保持绘制
- 空闲帧仍交还原版
- 其余七个元素各自都能保持 HUD 绘制

布局入口部分覆盖四种占用组合，断言等待行不与任何已占用行相同。

`HoverTextLanguageTest` 的必需键加入 `operation_submit_pending`，保证两个语言文件都存在该键。

主线程已运行定向 Gradle 测试：`test --tests '*WorkspaceSubmissionHudTest' --tests '*HoverTextLanguageTest' --no-configuration-cache --console=plain`，最近一次成功耗时 8 秒。此结果不代替完整测试与实机验收。

## 后续复核

等待期间，填充模式改用 `fill_mode_status`，保留状态但移除不可用的按键提示。任务行通过 `showsCancelHint` 隐藏等待期间不可用的取消键。新增测试覆盖这两项行为。

2026-09-16 再次检查当前代码：`renderCrosshairHud` 的面提示通过 `crosshairActionLines(face, workspaceLocked)` 处理锁定，轴提示通过 `acceptsNewAction(workspaceLocked)` 处理锁定。因此，无条件调用此绘制方法本身不构成“等待期间仍提示轴操作”的证据，不再为此添加整段屏蔽。

同一轮核实了三处分支的守卫状态：

| 分支 | 守卫 |
|---|---|
| 几何悬停文字 | 无，已补上单点守卫 |
| 面操作行 | 有，`crosshairActionLines(face, workspaceLocked)` 在锁定时返回空列表 |
| gizmo 提示 | 有，`acceptsNewAction(workspaceLocked)` 在 y 计算之后判断 |

不能由面分支的守卫推断 gizmo 分支。两处是独立判断。补充的守卫只作用于几何悬停文字，不是整段屏蔽。

## 边界

- 未做实机验收。静态阅读不能证明等待行在真实延迟下的观感，也不能说明该行是否闪动。
- 未测量等待窗口的实际长度。
- 活动行与本地提交是否描述同一次操作，本类不判断。它只避免同时画两行。
- 普通工作区状态行已修改，尚未完成测试与实机验收。
