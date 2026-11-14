# 工作区快捷键绑定迁移（更新至 2026-11-15）

## 本批范围

- `bindings/default/workspace.json` 接管 Ctrl+A/C/V/D/R/F 的按键、准入、编辑中拦截和提交中提示。Ctrl+X 原来没有实现，本批保持无默认行为。
- `workspace-history.json` 接管 Delete／Backspace、Ctrl+Delete／Backspace、Ctrl+Z 和 Ctrl+Y。编辑或提交中的 Ctrl+Z 不得进入世界撤销；空工作区只要还有本地历史，仍优先撤销本地步骤。Ctrl+Y 保持原来的准入，不额外增加编辑／提交状态限制。
- 输入边界冻结所属模式、KEY 路由、PASTE_WORKSPACE 准入、部件／草稿、编辑／提交状态、本地撤销深度和物理修饰键。绑定核心不查询全局会话。
- `WorkspaceCommand` 只表示原有原子操作；输入适配器调用原有剪贴板、选择集与变换能力，未改变操作结果。
- `WorkspaceKeyboardSemantics` 已删除。世界撤销／重做仍在发送边界检查连接与协议能力；仅在连接存在时结束选区手势。
- 本地撤销和世界撤销是独立绑定。只禁用本地规则时，较低优先级的世界规则仍可接管；完全放行该键可同时禁用对应规则，或把本地规则覆盖为 `fastformer:vanilla`。

## 确认子树

- `confirmation.json` 接管 Enter／数字键盘 Enter 的选区确认、起形提交和几何确认。恢复提示仍只默认绑定主 Enter，未新增数字键盘恢复行为。
- 恢复规则先于忙碌路由检查；普通确认仍受所属 KEY／SUBMIT 路由、界面、玩家、连接、确认通道和原版指针占用事实约束。选区确认保持原有独立准入，不额外要求放置确认通道。
- 起形提交数据在按下事件入队前捕获，重绑后的键也会携带同一快照。`QuickShapeSubmissionController` 不再校验物理 Enter 键码，只执行已获准的提交。
- 绑定系统按需构建键上下文，一次匹配过程中只冻结一次。未绑定或已完全禁用的物理键不会构建确认指针事实。
- 删除 `SubmissionKeyboardSemantics` 和输入入口中的 Enter 后备分支；禁用起形或选区确认后，不得落入通用放置确认。

## 取消与冲突提示

- `cancellation.json` 接管 Q 的物理捕获与排队后分派：活动长按绑定优先取消；提交等待只提示；选区取消栈末内容；其他会话走所属取消能力。只有请求取消时才冻结恢复提示的 dismiss 范围。
- 清理入口保护的规则只放行原版，不伪造取消意图。禁用／重绑后不恢复硬编码 Q；完整重绑需同时覆盖四条 capture 分支。已消费的重绑键若对应丢弃映射，会清除原版 click。
- `history-conflict.json` 接管 Enter／数字键盘 Enter 覆盖、Ctrl+Enter 跳过、Q 取消及提示期间 Ctrl+Z/Y 阻断。发送仍按 token 原子防重；活动长按绑定的取消优先于冲突提示。
- `CancelInputSemantics` 已删除。新默认资源与排队清理测试已通过 2026-11-15 的 k 轮整批回归；下述此前阶段数字仅对应其原提交。

## 按键生命周期

- 移动键预捕获与实际执行匹配同一份绑定。预捕获使用隔离的无副作用命令对象，不编辑工作区、不显示提示；重绑到 W/A/S/D 后仍能清除相应移动映射。
- 预捕获只为清理移动映射而把 repeat 视为 press；实际 repeat 不运行操作。物理 release 仍无条件清理移动映射，不依赖 Ctrl 的释放顺序。
- `fastformer:vanilla` 终止 FF 匹配并放行原版，不把该物理键算作已消费的移动键。禁用／重绑不会回退到旧物理映射。
- 原版副手交换键若由工作区接管，在按下时移除其原版 click；不再专门硬编码 Ctrl+F。

## 验证状态

- `DefaultWorkspaceBindingsTest`、`DefaultWorkspaceHistoryBindingsTest`、`DefaultConfirmationBindingsTest` 直接读取默认 JSON：组合对照、全部真实输入路由、优先级、禁用／重绑、vanilla 放行、release／repeat 和惰性上下文均有覆盖。旧规则测试中的空工作区撤销、忙碌状态不落世界及真实模式观察回归已迁入数据绑定测试。
- `BindingFixtures` 和 `RecordingBindingCommands` 提供共享测试装配与无副作用命令记录。`MovementKeyInputTest`、入队事件和起形提交测试保留。
- 2026-11-15 完整 `check --no-build-cache --offline` 通过：483 个套件、2648 项单测，0 失败、0 错误、1 跳过。架构检查新增债务 0，现存债务 912。`verifyArchitectureStrict` 仍因这些旧债务失败；整份手册尚未验收。
- 新世界 `build/gametest-keyboard-bindings-20261115` 的 323 项必需 GameTest 全部通过，日志为 `.temp/keyboard-bindings-gametest-20261115.log`。该服务端回归不替代真实客户端键盘与渲染实测。
- 最新 k 轮完整 `check` 通过：503 个套件、2736 项单测，0 失败、0 错误、1 跳过；`build/gametest-refactor-20261115-k` 的 326 项必需 GameTest 全部通过。日志 `.temp/refactor-final-20261115k.log`；架构新增债务 0，现存债务 368，单独执行的严格检查仍失败。此结果包含取消、冲突、几何、空闲、滚轮及选区绑定，仍不替代真实客户端实测。
