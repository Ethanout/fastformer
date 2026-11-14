# 工作区快捷键绑定迁移（2026-11-14）

## 本批范围

- `bindings/default/workspace.json` 接管 Ctrl+A/C/V/D/R/F 的按键、准入、编辑中拦截和提交中提示。Ctrl+X 原来没有实现，本批保持无默认行为。
- 输入边界冻结所属模式、KEY 路由、PASTE_WORKSPACE 准入、部件／草稿、编辑／提交状态和物理修饰键。绑定核心不查询全局会话。
- `WorkspaceCommand` 只表示原有原子操作；输入适配器调用原有剪贴板、选择集与变换能力，未改变操作结果。
- 删除旧 `WorkspaceKeyboardSemantics` 的六组物理键与对应准入分支；该类暂只保留 Delete／Backspace／Ctrl+Z。Enter、Q 和其他操作树仍待迁移。

## 按键生命周期

- 移动键预捕获与实际执行匹配同一份绑定。预捕获使用隔离的无副作用命令对象，不编辑工作区、不显示提示；重绑到 W/A/S/D 后仍能清除相应移动映射。
- 预捕获只为清理移动映射而把 repeat 视为 press；实际 repeat 不运行操作。物理 release 仍无条件清理移动映射，不依赖 Ctrl 的释放顺序。
- `fastformer:vanilla` 终止 FF 匹配并放行原版，不把该物理键算作已消费的移动键。禁用／重绑不会回退到旧物理映射。
- 原版副手交换键若由工作区接管，在按下时移除其原版 click；不再专门硬编码 Ctrl+F。

## 验证状态

- 新增 `DefaultWorkspaceBindingsTest`：实际 JSON 的旧行为组合对照、禁用／重绑、vanilla 放行及 release／repeat 边界；保留 `MovementKeyInputTest` 的修饰键先后释放与持续移动回归。
- 本子任务未运行 Gradle。主任务统一验证：`test --tests "*DefaultWorkspaceBindingsTest" --tests "*WorkspaceKeyboardSemanticsTest" --tests "*MovementKeyInputTest" --tests "*DefaultToolBindingsTest" --tests "*BindingSystemTest" verifySourceLayout`，随后执行架构检查。
