# 首写后重启恢复验收

实施目录：`D:/重要的资料/项目/__mc/fastformer`。本次使用当前未提交工作树，不以 HEAD 代表完整运行版本。

## 执行与结果

运行 `./scripts/Test-RecoveryRestart.ps1 -RunDirectory run/recovery-audit-20260919-mailbox`。
脚本新建隔离测试存档，没有使用玩家存档。
普通放置、工作区放置和选区移动分别在三个维度执行。
首写后，测试确认三个 journal 已持久化但未封口，并保存部分写入的世界。
测试通过 `Runtime.halt(17)` 结束服务器，再用第二个进程加载同一存档。

恢复阶段正常退出。`run/recovery-audit-20260919-mailbox/recovery-process-result.txt` 内容为 `PASS`。
服务器日志保留在该目录的 `logs/latest.log`。

## 已检查的断言

- 放置和工作区目标恢复为石头。
- 选区源位置恢复为金块，目标位置恢复为石头。
- 恢复 journal 清理完毕，世界写入门禁开放。
- 三个维度无残留写入预约，内存预约为零。

## 未覆盖

本次只覆盖首写后的未封口日志。它不覆盖封口提交、历史 batch/index 发布、方块实体、流体、外部修改和磁盘故障。
它不模拟操作系统断电，也不替代客户端验收。对应 TODO 保持未完成。
