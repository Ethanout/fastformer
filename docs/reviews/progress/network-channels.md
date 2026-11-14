# 网络通道与传输边界（2026-11-14）

## 本阶段

- `core.net.InboundChannels` 拥有类型化路由、投递时校验、tick 邮箱及失效处理，不依赖 Minecraft。客户端装配只声明接收类型、接受条件和处理函数。
- 删除原 `InboundPayloadQueue`，不保留转发别名。纯通道测试覆盖连接替换、批次失效、重入、异常、冻结注册和实例隔离。
- 原 `FastPlaceNetwork` 已移为 `server.network.ServerNetworkSystem`，删除同步／清理／遗忘的转发方法。业务直接调用实际拥有状态的 `PreviewSync`；上传状态仍归 `UploadSystem`。
- 共用的 `common.net.ClientboundTransport` 只安装一次客户端接收器，不加载物理客户端类。
- `ServerGateway` 集中管理客户端能力查询及发送。`trySend` 对不支持的可选请求返回 `false`；严格的 `send` 保留传输异常。分块上传必须使用严格发送，由请求所有者处理失败，不能静默丢失分块。
- 严格架构守护禁止其他客户端类直接依赖 `PacketDistributor` 或 `NetworkRegistry`，并测试字段、签名、类常量和方法引用。

## 验证

本批仍在整合。编译、单测、GameTest 和架构结果以主进度中本阶段的最终记录为准，不能沿用 `dda4cc3` 的验证结果。

## 余项

- 协议声明、codec 与两端处理器仍需进一步减少重复装配。
- 设置页尚未完全由设置注册表生成。
- 输入与提交的业务请求生命周期仍需继续迁出旧输入总入口；本次传输收拢不代表该入口已完成拆解。
