# BUG-AS 修复：源内容变化后的调整反馈

## 问题

源方块变化后，选区面仍显示拖动提示，按下鼠标却不开始拖动，也不给出原因。玩家只能重复尝试。

原调用链：

1. `WorkspaceInteractionResolver.resolveFace` 用 `canAdjustAabbFace` 只检查部件几何能力，据此设置 `adjustable`。
2. `OperationInteractionIntent.Face.hoverTextLines` 因此继续显示拖动提示。
3. 按下鼠标时 `FastPlaceClientInput.beginWorkspaceFaceDrag` 调用 `sourceSnapshotMatches`。该检查重新捕获选区并与保存的方块映射比较。
4. 比较失败时方法直接 `return false`，没有任何提示。

点调整路径同样静默：`ClientOperationController.adjustActiveAabbPoint` 在源检查失败时返回 `false`，调用者无法区分“源已变化”与“没有可调整目标”。

## 保留的保护

源快照比较是一项正确保护。本轮不删除比较，不自动覆盖或重采样玩家内容，也不新增不存在的刷新操作。

客户端方块映射只是预览。新提示只说“源方块已变化，请重新框选以再次读取”，不把客户端预览描述为完整的服务端快照。

编辑起点的源检查只在客户端。控制器在面拖动和点调整开始时重新捕获选区，并与部件保存的方块映射比较。该检查不用于堆叠手势，也不用于提交。

服务端不做“世界仍等于编辑开始时的源映射”检查。服务端做写入期实时保护。任务在校验之后记录每个目标格的实时前像，包括清除格与写入格。写入时要求该前像仍匹配当前世界。这表示本任务捕获后该格未变。这不是编辑起点身份。

校验器的实时查找参数只做非空检查，从不读取世界。测试 `doesNotReadLiveWorldWhenSubmittingSavedWorldSnapshot` 要求这个行为。世界来源部件的方块键是清除坐标。目标值是变换后的客户端快照。这是保存快照提交契约。

不要从交互规则第 11 行恢复实时比较。该行是设计规则。现有测试锁定当前契约。核心发现文档已说明这不是自动漏洞。

以后的源引用模型若把编辑起点检查放到服务端，那是新契约。它不是本轮反馈修复的一部分。控制器源状态注释仍写“服务端在应用前再次捕获并校验源”。那句话夸大了编辑起点检查。本轮不改生产注释。

## 修改内容

失败原因改为显式结果，不再由单个 boolean 同时表示“是否消费输入”和“为何失败”。

| 文件 | 修改 |
|---|---|
| `ClientOperationController.java` | 新增 `SourceState`、`AabbAdjustDecision`、`aabbAdjustDecision(part)`、`aabbAdjustFailureKey(decision)`；`adjustActiveAabbPoint` 由 boolean 改为返回决策；客户端 level 缺失时记录一次警告 |
| `FastPlaceClientInput.java` | `beginWorkspaceFaceDrag` 返回决策；面拖动与点调整显示失败原因；选中回退与鼠标路由行为不变 |
| `ClientInteractionFeedback.java` | 新增 `showAabbAdjustFailure(minecraft, decision)` |
| `en_us.json`、`zh_cn.json` | 新增 `operation_source_changed`、`operation_adjust_unavailable` |

### 决策与提示

| 决策 | 含义 | 提示 |
|---|---|---|
| `READY` | 源检查通过，等待调用者开始编辑 | 无 |
| `DRAG_STARTED` | 面拖动已开始 | 无 |
| `ADJUSTED`、`UNCHANGED` | 点调整已执行 | 无 |
| `SOURCE_CHANGED` | 源方块与保存的映射不同 | `operation_source_changed` |
| `SUBMISSION_PENDING` | 已有提交未结束 | `operation_submit_pending` |
| `NO_TARGET` | 没有可调整的选区或参数无效 | `operation_adjust_unavailable` |
| `ENVIRONMENT_UNAVAILABLE` | 客户端 level 不可用 | 无提示，记录一次警告 |
| `EDIT_UNAVAILABLE` | 已有编辑会话占用 | 无提示 |

`ENVIRONMENT_UNAVAILABLE` 不显示 actionbar 文本。该状态表示客户端环境问题，重复提示没有帮助。`EDIT_UNAVAILABLE` 属于正在运行的拖动手势，玩家已经有对应提示。

### 未完成的部分

`beginWorkspaceGizmoDrag` 有 3 处静默 `false`。只读复核后更正如下：

- 提交等待分支（1660-1662）不可达。`onMouseButton:743-746` 在 `dispatch(POINTER) == BLOCKED` 时提前返回。SUBMITTING 的 POINTER 路由是 BLOCKED，依据 `ClientInputStateMachine:180` 把 POINTER 归入 regular，以及 147 行规定 SUBMITTING 的 regular 是 BLOCKED。`acceptsObservation` 对 SUBMITTING 返回 false（196-199 行），所以提交期间状态不变。执行流到不了该分支，加提示无效。
- `beginEdit` 失败（1677-1679）只在提交锁定或已有编辑会话时触发。这两种情况都不进入该鼠标路径，属防御分支。
- 径向退化（1686-1689）要求命中点与 gizmo 中心重合。环命中由 `hitRing` 产生，半径不为零，实际不可达。该分支已调用 `cancelTransformGesture` 回滚。

以上三处均未修改。面路径的提交等待分支（1704）同样不可达。

面悬停提示仍承诺可拖动。让悬停反映源状态需要把源版本状态交给部件能力模型，并且不能每帧扫描整个选区，属于共享修复计划的第 3 条。

## 验证

主线程运行了集成验证：

```
.\gradlew.bat test --no-configuration-cache --console=plain
→ BUILD SUCCESSFUL in 30s
→ compileJava、compileTestJava、test 均执行
→ build/test-results/test XML：226 suites / 1221 tests / 0 failures / 0 errors / 1 skipped
```

该结果是本次当前树的集成测试结果。它证明改动能编译，且既有测试与新增测试通过。它不证明游戏内交互，也不覆盖此后的代码改动。

新增用例覆盖：无可调整部件归为 `NO_TARGET`；客户端 level 缺失时不谎报源变化；点调整的无效参数归为 `NO_TARGET`；只有玩家可修复的阻塞才产生提示键；无客户端时显示调用不抛异常。

## 边界

- 未做双玩家实机验收。源方块变化、源恢复原样、选区外变化三种实机场景尚未截图确认。
- 测试不证明提示在游戏内可见，也不覆盖悬停提示与失败提示的一致性。
- 源检查的重复捕获成本未测量，归入已有 BUG-F。
- 本轮不把编辑起点源检查放到服务端，也不改保存快照提交契约。
