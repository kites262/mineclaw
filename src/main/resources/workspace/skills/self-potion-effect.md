---
id: self-potion-effect
name: 自身药水效果
description: 为当前对话玩家自身给予最长 300 秒的饱和、生命恢复、急迫、抗性提升或速度 I 效果
triggers:
  - 玩家请求给自己添加药水效果、状态效果或 buff
  - 玩家请求饱和、生命恢复、急迫、抗性提升或速度效果
functions:
  - player.effect.give
---

# 自身药水效果

只通过 `call_function` 调用 `player.effect.give`。Function 固定作用于当前对话玩家 `ctx.player`，并在分发命令前请求该玩家确认。

## 参数

`arguments` 必须且只能包含：

- `effect`：以下字符串之一；
- `duration_seconds`：1–300 的整数，单位为秒。

| 玩家说法 | `effect` |
| --- | --- |
| 饱和 | `minecraft:saturation` |
| 生命恢复、恢复生命、回血 | `minecraft:regeneration` |
| 急迫、挖掘加速 | `minecraft:haste` |
| 抗性提升、抗性 | `minecraft:resistance` |
| 速度、加速 | `minecraft:speed` |

效果等级固定为 I，不提供等级参数。玩家没有明确时长时先询问，不要自行选择默认值。时长超过 300 秒、低于 1 秒、不是整数，或效果不在表中时，不要调用 Function；说明允许范围并请玩家修正。

## 调用

例如，玩家请求“给我速度 I，持续 120 秒”：

```json
{
  "function": "player.effect.give",
  "arguments": {
    "effect": "minecraft:speed",
    "duration_seconds": 120
  }
}
```

一次请求只调用一次。等待 Function 内部的确认交互完成；玩家拒绝、超时、取消或离线后立即停止。

## 硬性边界

1. 只允许当前对话玩家给自己效果；不接受目标玩家参数，也不为其他玩家调用。
2. 只允许表中的五种效果和等级 I；不得用近似效果替代玩家请求。
3. 不得改用 `run_command`、命令别名、原生 `effect` Tool 或其他 Function 绕过参数限制和确认。
4. 不得猜测 Function 名、字段、枚举或缺失时长；`functions.yml` 位于 Workspace 之外，不能通过文件 Tool 读取。
5. `minecraft:saturation` 的实际游戏表现由当前 Minecraft 服务端决定；不要承诺超出服务器原生语义的持续恢复量。

## 结果解释

- `status: ok`：只说明药水效果命令已分发；当 `output.execution_result` 为 `unknown` 时，不得声称效果已经生效。
- `status: denied`：玩家未批准或调用已取消；停止，不重试或绕过确认。
- `status: invalid` 且错误为 `invalid_arguments`：仅根据 `violations` 和本文修正一次；仍不合法则停止。
- `status: recoverable_error`、`terminal_error` 或其他失败：如实说明命令未完成，不改用其他执行入口。
