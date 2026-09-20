# 面角度命令的提示键修正

日期：2026-09-16。分类：提示文字缺失。

`FastPlaceCommandRegistry` 的两处角度命令失败分支引用了不存在的 `fastformer.message.face_mode_angle_unavailable`。当当前面模式不支持设置角度时，玩家会看到原始键名。

两处调用改用已有的 `fastformer.message.face_angle_unavailable`。中文和英文语言文件均已包含该键，未改变命令行为。

验证：扫描 Java 中静态引用的 message、hud、screen、setting、tooltip 键，并核对中英文语言文件。修复后此范围没有缺失键。动态拼接的键不在本次扫描范围内。

文稿检查：STE 0.00；中文检查覆盖有限。
