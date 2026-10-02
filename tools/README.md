# 开发工具

- `recovery/Test-RecoveryRestart.ps1`：独立进程恢复测试。默认运行目录为项目下的 `run/recoveryProcessTest`。每次测试必须使用不存在的新目录。
- `voxel_lab/`：体素算法实验、诊断和 Python 测试，使用说明见该目录的 README。
- `analysis/scan_tinker_families.py`：扫描原版方块 ID 的 3～16 字符前后缀、模型属性、共享材质及转换映射，输出到 `.temp/tinker-scan/`。运行方式与分析见 [扳手家族方案](../docs/plan/tinker-families.md)。

在项目根目录运行恢复测试：

```powershell
./tools/recovery/Test-RecoveryRestart.ps1 -RunDirectory run/recovery-first-write -Scenario FirstWrite
```

脚本会启动崩溃阶段与恢复验证阶段。CI 覆盖脚本支持的七种场景。
