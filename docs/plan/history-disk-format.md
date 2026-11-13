# 历史与恢复文件格式

Alpha 阶段只读写当前格式，不保留旧格式读取器或迁移器。试玩服务端旧数据允许清除。

## 统一文件信封

历史与恢复文件共用 `core.persist.Envelope` 和 `EnvelopeFile`。24 字节头依次保存 magic、格式版本、Minecraft `DataVersion`、载荷长度（各 4 字节）及 CRC32C（8 字节）。CRC32C 覆盖前 16 字节元数据与实际保存的载荷字节。历史 magic 为 `FFH2`；恢复 manifest、segment、seal、correction 分别为 `FFRM`、`FFRS`、`FFRL`、`FFRC`。各文件种类的信封版本均为 1，与内部方块批次／恢复载荷版本独立。

读取同时检查调用者预算、文件实际大小、截断、尾随字节和 CRC32C。完整性验证成功后，才把格式版本或 DataVersion 不符分类为已知不兼容；版本字节损坏不能绕过损坏保护。流式验证只使用固定小缓冲。`EnvelopeFile` 经 `AtomicFiles` 发布，编码或写入失败不替换原文件。旧 `FFH1` 和无信封压缩 NBT 不再读取。

## 历史批次

`WorldChangeBatch` 当前写入批次 v2，必填 `Type = fastformer:block_change`，保存 Minecraft `DataVersion`、维度、操作 UUID、位置、方块／流体调色板、变更前后状态和方块实体 NBT。`EntryCodecRegistry` 按 Type 分派，`BlockChangeEntryCodec` 校验方块数据。调色板须通过运行中的注册表读取。不存在的方块、未知属性或非法属性值会报错，不能降级为默认方块状态。

只接受批次 v2、当前 Minecraft `DataVersion` 与已注册的 Type。v1、缺失 Type、未知 Type、缺失或不同的数据版本均拒绝读取；不补版本、不执行 DataFixer 升级。方块与流体调色板按注册表与属性严格校验。压缩元数据必须显式保存 Cluster、Actions、CompressionStage，不再归一化旧分组键或推断缺失阶段。

`HistoryBounds` 是可重算的区域缓存。缓存缺失或损坏时仍从实际条目重建，不能让区域消失；这属于当前数据的鲁棒性处理，不是旧格式迁移。

`HistoryEntryFormat` 在 NBT 字节边界执行编码／解码预算、截断及尾随字节检查，通过冻结的 `EntryCodecRegistry` 返回真实条目类型。生产仓库使用 `HistoryEntry` 边界，要求条目的操作 UUID 与索引引用的批次文件一致。方块 Codec 和方块恢复日志仍只支持上述当前格式；此次通用层调整不添加旧格式读取器。

## 恢复分段

恢复四类文件均由 `common.persistence.NbtEnvelopeFile` 将压缩 NBT 存入统一信封，分别限制压缩后的字节数、压缩前的序列化字节数和解码内存。segment 的 NBT 保存 `OperationId`、必填整数 `Sequence` 和复合标签 `Payload`；内部快照载荷仍为 v2，严格检查当前 DataVersion。文件信封验证成功后才解码，之后检查操作、序号和载荷类型。

seal 的集合摘要使用 CRC32C，按顺序输入每段的序号（4 字节）和已验证的信封校验值（8 字节），均为大端序。correction 必须保存对应的 `OperationId`、`BaseCount` 和 `BaseDigest`，绑定完整分段集合，不再只绑定首段文件。旧 CRC32、SNBT 校验、`31*h+crc` 聚合和 `BaseSha256` 路径均已删除；不提供旧格式回退。seal 决定提交重放或未提交回滚，两条路径均先验证集合身份及全部修正位置，再修改世界。

## 发布与隔离

所有已迁移的写入使用 `AtomicFiles`：同目录临时文件 → flush／force → 原子替换。原子移动失败即报错，不回退到非原子覆盖。平台支持时同步父目录；Windows JDK 不能打开目录句柄时记录一次限制，仍保留文件 force 与原子替换。

完整性验证成功但格式版本或 DataVersion 不受支持时，历史存储隔离对应文件，恢复启动扫描隔离对应操作目录，移入同级 `quarantine/` 并保留原因，不执行迁移。隔离历史批次不删除顺序索引。读取器不自动清除未知文件；未知 magic、损坏、校验失败和未知方块属性继续触发安全检查，不能伪装成成功恢复。

CRC32C 用于文件完整性与集合绑定，不提供对恶意篡改的密码学认证。

## 客户端草稿与提交回执

只接受各自当前版本。草稿必须保存 `Unsubmitted` 与 `Selection.SecondPointOnly`；已提交草稿必须包含有效 `SubmissionId` 和 `Origin`。未提交草稿不能携带提交 ID。已应用的提交回执必须包含 `CleanupPending`。缺失这些字段时拒绝读取，不再按旧版规则补值；未提交草稿仍是当前格式支持的正常数据。
