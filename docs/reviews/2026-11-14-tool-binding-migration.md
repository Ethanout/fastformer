# 快速工具绑定迁移（2026-11-14）

## 已实施

- 默认规则位于 `assets/fastformer/fastformer/bindings/default/tools.json`，对应伪代码 `sessions/05-快速工具.md`。
- 快速替换的键盘、笔画按下／松开、原版 use 回调、tick 续画／结束／禁用与滚轮 pass 通过同一 BindingSystem 执行。工具继续拥有笔画状态。
- 智能填充的开始、确认、等待吞键通过绑定执行；动态交换副手键映射与物理键在同一轮优先级排序中匹配。
- 删除 ClientMode／ClientModeRegistry 的旧 onKey 分发、三个模式的硬编码键分支及 KeyInput／KeyView／KeyDecision。
- `input_phase` 保持入队前开始填充、入队后确认的先后关系；同一个 start_fill 绑定覆盖两个阶段，用户只需重绑或禁用一处。
- 绑定资源重载只取消活动的 deferred hold／profile，不再因普通工具键创建过 BindingSystem 而取消无关输入。版本检查覆盖每个分发边界，资源重载后不必等下一 tick；旧捕获的松开只作清理，不会被新 release 规则提交。

## 验证与边界

- DefaultToolBindingsTest 加载实际 JSON，逐组合比较旧键盘规则，并覆盖用户重绑／禁用、动态键映射、按下捕获与确认分离；另对替换 tick 的可用性组合及笔画／use／滚轮规则做对照。
- 已由主代理集成输入接入点，正在统一定向回归；本文尚不声称测试通过。
- 本批不代表第 9 节全部完成：其余会话操作树、填充鼠标／滚轮所有权的数据化和客户端手感实测仍需完成。
- 替换 mouse／scroll／interaction 适配器分别返回工具所有权与原版事件取消结果。默认笔画 begin／end 为“已处理且让原版继续”，维持原版 keyUse 状态；用户覆盖为 block 则取消事件。新增默认与自定义 block 对照。
