---
id: command-safety
name: 命令请求安全检查
description: 任何可能调用 run_command 的请求都应遵循的通用身份、审批和结果规则
triggers:
  - 玩家请求执行服务器操作
  - 某个任务 Skill 要求调用 run_command
---

# 执行前

1. 先用 `grep` / `read` 找到并阅读与具体任务匹配的 Skill；本文件不提供任何具体服务器命令。
2. 没有任务 Skill 或命令不明确时，先询问或说明 Workspace 未提供该能力，不要猜命令；目标玩家不明确时可用 `online_players` 核对准确账号名，仍有歧义则询问玩家。
3. 一次只处理一个明确操作和目标；`intent` 会作为“操作内容”展示给确认玩家，要用简短自然语言写清操作、执行者和目标。

# run_command 字段

- `command`：只能使用任务 Skill 明确给出的命令形式。
- `intent`：面向审批玩家，简短说明谁以什么身份对谁或什么对象做什么。
- `player`：涉及当前请求者或其他在线玩家时，先调用 `online_players`，填写其返回的精确账号名；控制台执行时设为 `null`。
  - **玩家自身副作用**（传送、设点、对自身生效、依赖玩家权限/位置/背包）→ 用对话玩家 `player`。
  - **控制台**只在任务 Skill 明确要求且控制台白名单允许时使用（`player: null`）；不要仅因为操作是查询或公共操作就自行切换到控制台。

AI 展示名不是玩家 ID。命令目标不等于命令执行者：执行者写在 `player`，目标按任务 Skill 写入 `command` 参数。

# 安全与审批

- 不得使用命名空间、大小写、额外空格、别名、转发或间接执行来绕过白名单和审批。
- 指定其他玩家为执行者时，Mineclaw 会向该玩家请求审批；不得代替对方批准。
- `pending_approval` 只表示请求已进入等待，不能声称操作已经完成。

# 结果解释

- `dispatched`：只表示命令已被命令系统接受；当 `execution_result: unknown` 时只能说明“已分发”，绝不能说操作完成或已经生效。
- `dispatch_status` 会区分 `accepted`、`player_offline`、`command_not_found`、`rejected`、`exception` 和 `unknown`；按实际字段解释，不要把一种失败改写成另一种。
- **反馈可见性**：控制台 `feedback` 可包含分发返回前同步产生的命令输出（例如在线玩家列表），可如实转述，但不能证明全部副作用；玩家分发不回传游戏内反馈，通常只能看到分发是否被接受。
- `denied` / `invalid`：说明限制或参数问题，不尝试绕过。
- `timeout`：说明审批或执行已超时，不能视为成功。
- `terminal_error`：如实说明未完成；除非错误明确可修正，否则不要重复调用。
