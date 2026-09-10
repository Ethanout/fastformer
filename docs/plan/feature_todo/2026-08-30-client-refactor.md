# 客户端和会话重构（2026-08-30）

## 范围

本次重构拆分客户端输入、操作、渲染、会话、网络和服务端任务代码。目标是让每个文件只有一个清晰职责，并让包名表达模块边界。

## 已完成

- 客户端会话改为玩家身份持有的状态树。
- 工作区、选区、剪贴板、变换和预览分别放入对应包。
- 输入拖动、渲染缓存、几何预览、HUD 和网络 payload 分层。
- 服务端会话、工作流、任务、世界历史和恢复日志分层。
- 历史 `docs/superpowers/` 计划不再作为当前设计来源。

## 当前包边界

```text
client/
  input/
  operation/{clipboard,controller,model,preview,selection,transform,workspace}/
  render/{cache,core,geometry,guide,hud,interaction,mesh,model,state,type}/
  session/tree/
fastplace/{command,events,geometry,generation,session,task,workflow,world}/
network/{client,payload/{geometry,operation,placement,preview,settings,world},sync,transfer}/
```

## 验收边界

自动化测试和 `verifyModJar` 只验证代码和构建。客户端选区、Alt 手势、几何预览、工作区变换、历史恢复和断线行为仍需游戏内验收。
