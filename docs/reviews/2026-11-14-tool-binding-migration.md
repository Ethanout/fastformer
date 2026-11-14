# 快速工具绑定迁移（2026-11-14）

## 已实施

- 默认规则位于 `assets/fastformer/fastformer/bindings/default/tools.json`，对应伪代码 `sessions/05-快速工具.md`。
- 快速替换的键盘、笔画按下／松开、原版 use 回调、tick 续画／结束／禁用与滚轮 pass 通过同一 BindingSystem 执行。工具继续拥有笔画状态。
- 智能填充的开始、确认、等待吞键通过绑定执行；动态交换副手键映射与物理键在同一轮优先级排序中匹配。
- 删除 ClientMode／ClientModeRegistry 的旧 onKey 分发、三个模式的硬编码键分支及 KeyInput／KeyView／KeyDecision。
- `input_phase` 保持入队前开始填充、入队后确认的先后关系；同一个 start_fill 绑定覆盖两个阶段，用户只需重绑或禁用一处。
- 绑定资源重载只取消活动的 deferred hold／profile，不再因普通工具键创建过 BindingSystem 而取消无关输入。版本检查覆盖每个分发边界，资源重载后不必等下一 tick；旧捕获的松开只作清理，不会被新 release 规则提交。
- 智能填充的鼠标、世界交互和滚轮由模式显式委派给数据规则。默认全部拦截；禁用单条规则或配置 `fastformer:vanilla` 可放行原版。工具所有权与事件取消结果分开，放行时不会误入其他 FF 会话分支。
- `binding/tool` 只接收冻结上下文和类型明确的分发接口，不查询输入会话、放置系统或 Minecraft。输入边界负责采集事实。KEY、取消和提交准入仍由模式策略控制。

## 验证与边界

- DefaultToolBindingsTest 加载实际 JSON，逐组合比较旧键盘规则，并覆盖用户重绑／禁用、动态键映射、按下捕获与确认分离；另对替换 tick 的可用性组合及笔画／use／滚轮规则做对照。
- 主代理已集成输入接入点。完整 check 与新目录 312 项必需 GameTest 通过；最后的重载补丁定向复验 `.temp/architecture-binding-reload-final.log` 退出码 0，含默认工具绑定 9 项测试。阶段已提交并推送：`b0916e5`（与客户端反馈／调度迁移一并交付）。
- 填充规则补充已完成输入接入，新增工具所有权、默认拦截／用户放行、额外鼠标键、同帧重载、任务准入与替换松开收尾测试。定向 `ToolBindingInputTest`、`DefaultToolBindingsTest`、`BindingSystemTest`、`ClientMode*Test` 重跑通过，日志 `.temp/architecture-binding-tool-input-retry.log`。首次运行已通过主源码／测试源码编译及 `verifySourceLayout`，随后因 Gradle 测试输出索引缺失失败；重跑退出码为 0。整批架构检查与全量回归由主任务统一执行。
- 本批不代表第 9 节全部完成：其余会话操作树和客户端手感实测仍需完成。
- 替换 mouse／scroll／interaction 适配器分别返回工具所有权与原版事件取消结果。默认笔画 begin／end 为“已处理且让原版继续”，维持原版 keyUse 状态；用户覆盖为 block 则取消事件。新增默认与自定义 block 对照。
- 后续全量预检暴露两处旧测试仍假定填充的三类输入由状态机直接 BLOCKED。已更新状态表，并让 `SmartFillInputStateTest` 联合真实默认 JSON、模式委派和工具适配器验证默认鼠标／交互／滚轮仍全部拦截；取消后撤销委派，KEY／SUBMIT 仍阻止。补丁尚待主任务重跑。
