# 架构重构进度归档：早期记录与验证

返回 [进度索引](../2026-11-13-architecture-refactor-progress.md)。按时间顺序追加，不在此处维护当前计划。

## 已完成

- 用 `Registry`、`ClientboundTransport`、`ClientInboundSystem`、`InboundPayloadQueue` 替换客户端反射分发。20 种载荷通过类型明确的注册关联处理器与校验规则，不再维护载荷 switch 和成组重载。公共侧仅引用公共传输入口。
- 保留三种不同校验：建立预览会话、已建立会话的回调、当前连接回执。校验仍在 tick 消费时执行。切换世界使当前批次失效；重入投递延后；处理异常丢弃批次剩余事件，并允许后续 tick 继续处理。
- 输入与选区共享所属玩家的手势状态。修复无世界时两套备用状态互不一致的问题，备用状态也由会话所有者一起装配。指针视图、输入分发和手势结算使用传入会话。
- 输入重置复用指针清理，补上 `operationTransformCapture` 的释放，删除重复取消。
- 两套重连等待复用 `ReconnectSnapshotWindow`，保留 40 tick 上限、快照优先与超时后保留草稿的行为。
- 补充注册、投递、旧连接、批次失效、异常恢复、玩家隔离、备用状态、变换捕获清理及等待边界测试。新增对应源码依赖约束。

## 验证

- `./gradlew.bat --offline check`：通过。2204 项测试，0 失败，1 跳过；另包含 GameTest 源码编译、架构约束与发布产物检查。
- 独立目录运行快速替换、空闲入口、扳手入口 GameTest：26 项全部通过。
- 全量 `runGameTestServer`：在独立新目录中 268 项全部通过。此前复用旧测试世界时，故障注入留下的 `index.dat` 目录造成 `AccessDeniedException` 并干扰后续测试；隔离历史持久化测试 13 项通过，随后新目录全量通过。该结果对应后续持久化改动之前的代码，不能代替最新版本的全量验收。
- `./gradlew.bat --offline runClient`：已启动，完成客户端初始化、资源加载与声音引擎启动，未见启动异常。尚未验证进入世界后的全部交互。
- 日志与隔离脚本保存在 `.temp/architecture-*.log`、`.temp/architecture-client.init.gradle`，未改变正式构建文件的仓库配置。

## 本轮验证

- architecture-async-runtime.log：新目录全量 277 项 GameTest 通过，覆盖 B10／B11、当前格式隔离与异步预览。
- architecture-server-workers-check3.log、architecture-session-slots-check.log、architecture-session-admission-check.log：check 通过。
- architecture-admission-runtime.log：新目录全量 277 项 GameTest 通过，包含共享 gate、服务器工作线程和维度槽改动。首次运行暴露受控队列测试未推进新增压缩任务、原版水面放置被自身租约拒绝；均已修复并重跑通过。
- architecture-shutdown-futures-check.log、architecture-shutdown-runtime.log：check 和全量 277 项 GameTest 通过，包含排队 Future 取消、拒绝准入、回调／发布取消及历史持久化排序测试。
- 试玩存档尚未删除。测试均使用独立 .temp 目录；未提交或推送 Git。

剩余主线仍包括：服务端业务状态的完整迁移与任务运行单元、历史条目扩展及包内职责拆分、Intent／声明式协议、ClientScope／Mode／PreviewFrame／PointerResolver、完整操作树迁移、全包迁移与反射测试清理。
