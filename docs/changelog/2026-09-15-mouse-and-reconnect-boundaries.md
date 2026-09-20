# 鼠标与重连边界

- `MouseButtonInputSemantics` 负责 Alt 新建选区和普通中键确认的纯输入规则。
- Alt 新建选区仅接受左键、右键和中键的按下事件。
- 普通中键确认仅在建筑会话允许确认时生效。
- `MouseDragReleaseSemantics` 负责左右键释放时的拖拽、捕获和短按撤回优先级。
- `MousePressRoutingSemantics` 负责普通左右键按下的所有者优先级和原版让行决策。
- `DragAdvanceSemantics` 负责每 tick 拖拽所有权失效清理和推进顺序。
- Q 取消规则负责上下文许可、提交等待和恢复提示关闭决策。
- 命中检查、拖拽推进、网络发送和事件取消仍由输入处理器执行。
- 悬浮文字测试检查中英文资源中的 16 个必要键。
- 距离反馈在同一目标停留 100ms 后使用 3 倍计时倍率。目标变化和生命周期结束会复位倍率。
- 未确认提交遇到断线时保存持久草稿，但不保存锁、请求 ID、输入状态或编辑手势。
- 服务端选择身份不匹配时，客户端删除保存的草稿。
- operation preview/result 携带玩家、维度和服务器会话 UUID。
- 客户端同时检查入队连接、当前连接、玩家、维度和服务器会话。
- workspace 首包保存原始 scope，迟到结果不能使用玩家的新维度或新连接。
- building、geometry、activity 和 placement ack 复用相同 scope 门禁。
- geometry 和 activity 使用独立递增 revision。placement ack 先检查 scope，再检查 request ID。
- 防失败计划的输入、预览和历史职责拆分阶段完成。大型协调类仍保留必需的命中、发包和世界写入编排。

自动化测试覆盖按下、释放、拖拽推进、语言键、计时倍率、透明度连接和持久草稿数据边界。客户端画面与真实网络时序仍需实机验收。
