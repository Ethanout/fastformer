# 通用双击与容差参数（2026-11-15）

`BindingSystem.onButton` 现在识别 `double`，按物理按键分别配对。第二次按下必须经过松开；按键重复事件不算第二击。一次成功配对后消费首击，第三击重新开始。

普通按钮使用 `input.window_ms`。有指针快照的调用使用 `input.double_click`：`tolerance` 定义连续距离分档的 `from`／`below`、`window_ms`、`exact`、`edge_deg` 和 `edge_max_blocks`；`accept_if_any` 定义 `view_change_deg` 或 `hit_distance_blocks`。默认值保留不足 15 格的 200ms 精确命中，以及 15 格起的 300ms／0.5°／最多半格边缘余量；空间条件为视角不超过 2°或命中点间距不超过半格。

识别使用第一次按下的时间、环境、物品和冻结形状。短按长按仲裁补发时才记入原始首击，开始长按则清除首击。重载、reset 和作用域变化不复用旧配对。

空闲入口与服务端锚点已共用 `DoubleClickPolicy`，删除旧 `EntryDoubleClickTolerance`。空闲操作树与其资源参数接线仍待下一步完成；不能仅凭通用能力存在宣布空闲树完成。

另修复长按画线资源使用的 `double_second_press` 字段未注册的问题。新增测试直接读取实际 `hold_line.json`，检查字段、共存排除条件和原始时间补发。当前新增测试待统一回归。
