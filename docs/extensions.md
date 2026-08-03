# 扩展指南

Mineclaw 的扩展原则是把“知识”“模型入口”和“受信任副作用”分开。先选择正确层次，再写内容。

## 四个构件

| 构件 | 解决的问题 | 权限来源 |
| --- | --- | --- |
| AGENTS | 这个 Agent 是谁、平时怎样工作、如何表达结果 | 没有权限；只是启动指令 |
| Skill | 本服有哪些知识，某项已有能力何时、按什么参数使用 | 没有权限；只是按需指南 |
| Tool | 模型可直接调用的稳定 Java handler 或 Provider 能力 | Java 注册、配置开关、白名单/审批 |
| Function | 一项服主审核、可编排多步操作的业务能力 | JSON Schema、显式 capability、沙箱源码 |

推荐流程：先把稳定身份和共通安全规则写进 `AGENTS.md`；把每项业务写成小而完整的 Skill；需要可靠分支、并发审批或多条命令时，再实现一个 Function，并让 Skill 公开它的精确契约。

## AGENTS

发行版 [`AGENTS.md`](../src/main/resources/workspace/AGENTS.md) 已包含：

- “你是由 Mineclaw 驱动的 Minecraft Agent”这一背景；
- 先理解、查 Skill、补实时证据、再行动的工作节奏；
- Skill 不是授权书，执行结果决定措辞；
- 模型 `run_command` 与 Reviewed Function `command.dispatch` 的不同信任路径；
- Function 错误字段和 `dispatched ≠ 已生效`；
- 公屏可使用 MiniMessage 命名色与十六进制颜色。

服主可以增加服务器人格、世界观、活动入口和资料索引。不要把 API key、私有后台地址、Function 源码或长篇业务细节放进 AGENTS；这些内容会进入模型启动上下文，也会分散注意力。

## Skill

Skill 是 Workspace 中可发现的 Markdown 文档。推荐每个文件只描述一个能力，并包含 frontmatter：

```markdown
---
id: north-expedition
name: 北境远征
description: 组织北境遗迹的队伍、角色与集合流程
triggers:
  - 北境远征、组队、集合、选择角色
---

# 北境远征

当玩家明确要求创建远征队时，调用 Function `expedition.form`。

参数：
- `players`: 2–8 个准确 Minecraft 账号名，必须先用 `online_players` 核对；
- `destination`: 只能是 `north_ruins` 或 `frozen_gate`。

读取完整 Function 返回。只把 `dispatch_status: accepted` 描述为已分发；
`execution_result: unknown` 时不得声称已经传送或组队成功。
```

Skill 应写清：触发场景、准确名称、参数 Schema、前置观察、执行身份、审批对象、结果字段、失败停止条件和明确不支持的边界。不要只给“可以使用某插件命令”这样的模糊授权。

用 `/mineclaw functions validate` 检查 Skill 中的 Function 引用。发行版内置 [结构定位](../src/main/resources/workspace/skills/locate-structure.md) 与 [自助药水效果](../src/main/resources/workspace/skills/self-potion-effect.md)；仓库另有不会播种的 [KitesPlaces 示例](../examples/skills/kp-warps.md)。

## 本地 Tool 与 Provider Tool

`tools.yml` 只能描述 Mineclaw 已注册的九个 handler，不能从 YAML 新增 Java 实现：

| handler | 作用 | 副作用 |
| --- | --- | --- |
| `look_block` | 当前对话玩家准星方块与坐标 | 无 |
| `feet_block` | 当前对话玩家脚下方块与坐标 | 无 |
| `inventory` | 当前对话玩家的脱敏背包摘要 | 无 |
| `online_players` | 当前发起人和在线账号名 | 无 |
| `list` / `read` / `grep` | 检索隔离 Workspace | 无 |
| `call_function` | 按准确名称和参数进入 Function | 由目标 Function 决定 |
| `run_command` | 模型直接申请玩家/控制台命令 | 白名单或玩家审批后可能有 |

Provider 能力属于 `providers.yml providers.*.tools`，payload 按 Provider 原生格式进入请求。目前默认示例是 MiMo `web_search`。这类 Tool 不会转换为 function，也不能被 JavaScript Function 通过 `native_tool.call` 间接调用；Function 的 native capability 只面向本地 handler。

## 编写 Function

Function 只存在于 `functions.yml`，源码不会发送给模型。模型通过固定 `call_function` Tool 提供准确名称和 `arguments`，运行时再完成：

1. 精确、区分大小写地查找 Function；
2. 按声明的 JSON Schema 编译和校验参数；
3. 创建独立 Graal Context；
4. 检查每次 `api.invoke` 对应的 capability；
5. 在调用、超时、取消和结果边界记录审计身份与源码 hash；
6. 把结构化结果原样交回模型。

入口必须是：

```javascript
async function onCall(ctx, api) {
  return {
    status: "ok",
    output: {message: `requested by ${ctx.player.name}`}
  };
}
```

`ctx.args` 是校验后的参数；`ctx.player.name` 和玩家身份由运行时提供。Function 必须返回 JSON 可表示的 `{status, output}`。把业务失败表达为稳定 status，并在 `output` 保留 `error_code`、相关实体和上游状态，不要只返回一句模糊字符串。

### Capability

| capability | 对应调用 | 说明 |
| --- | --- | --- |
| `approval.request` | `action: "approval.request"` | 向准确在线玩家发起 confirm/select |
| `command.dispatch.console` | `action: "command.dispatch"` + console executor | Reviewed Function 的可信控制台命令 |
| `command.dispatch.player` | `action: "command.dispatch"` + player executor | Reviewed Function 的可信玩家命令 |
| `native_tool.call.<handler>` | `action: "native_tool.call"` | 只允许准确声明的本地 Tool |

`native_tool.call.call_function` 永远禁止，因此 Function 不能递归调用 Function。声明一个 disabled 但有效的本地 Tool 仍可使 capability 通过目录编译，但执行时会遵循该 Tool 的实际可用状态。

### 玩家确认与选择

确认：

```javascript
const decision = await api.invoke({
  action: "approval.request",
  input: {
    player: "Alice",
    interaction: {
      type: "confirm",
      title: "确认加入北境远征",
      message: "同意后，队伍将为你分配角色并准备集合。"
    },
    timeout_ms: 60000
  }
});
```

选择：

```javascript
const role = await api.invoke({
  action: "approval.request",
  input: {
    player: "Alice",
    interaction: {
      type: "select",
      title: "选择远征角色",
      message: "请选择本次远征职责。",
      options: [
        {id: "scout", label: "侦察"},
        {id: "guard", label: "守卫"},
        {id: "healer", label: "治疗"}
      ]
    },
    timeout_ms: 60000
  }
});
```

目标必须是准确在线账号；`select` 必须有 2–8 个唯一 option。timeout 支持 `1000–300000` 毫秒。成功时 `status` 为 `approved`，`output.value` 是 boolean 或 option id；拒绝、超时、离线、失效与取消会保留稳定状态及错误上下文。

### 调用本地 Tool

```javascript
const roster = await api.invoke({
  action: "native_tool.call",
  input: {name: "online_players", arguments: {}}
});
```

同时在 Function 上声明 `native_tool.call.online_players`。不要假定结果成功；先检查 `roster.status`，再按该 Tool 的当前结果 Schema 读取 `roster.output`。

### 分发可信命令

```javascript
const dispatch = await api.invoke({
  action: "command.dispatch",
  input: {
    executor: {type: "console"},
    command: "team join expedition Alice",
    intent: "把 Alice 加入已经确认的北境远征队"
  }
});
```

玩家 executor 的形状为 `{type: "player", player: "Alice"}`。Function 必须声明匹配的 `command.dispatch.console` 或 `.player`。

这条路径不读取 `whitelist.yml`：信任来自服主审核过的源码、source hash、严格参数和显式 capability。也正因如此，修改 Function 时必须像审查服务端代码一样审查所有字符串拼接、身份绑定、错误分支和副作用顺序。

命令结果至少要区分：

- `status`: 本次操作处于 dispatched、denied、invalid、cancelled 或 terminal_error 等哪一层；
- `output.error_code`: 失败的原始类别；
- `output.dispatch_status`: 是否被运行时接收；
- `output.execution_result`: 是否有执行层证据；
- `output.feedback`: 控制台同步反馈（玩家命令通常为空）。

`dispatch_status: accepted` 与 `execution_result: unknown` 只能表述为“已分发”。

## 复杂编排模式：多人远征

下面是设计蓝图，不是可直接投入任意服务器的命令脚本；把其中命令替换为你的队伍插件 API，并完成代码审核。

```text
输入 players[2..8] + destination(enum)
  │
  ├─ native_tool.call.online_players
  │    └─ 任一账号不精确/离线 → denied，零副作用
  │
  ├─ Promise.all(每人 approval.request select 角色)
  │    ├─ 拒绝/超时/取消 → denied，零副作用
  │    └─ 角色冲突 → 返回 recoverable_error，让 Agent 只重问冲突者
  │
  ├─ Promise.all(每人 approval.request confirm 最终方案)
  │    └─ 任一未批准 → denied，零副作用
  │
  ├─ command.dispatch.console 创建队伍
  ├─ 逐人分配角色（前一步失败立即停止）
  └─ 返回 approvals[] + roles[] + dispatches[] + 未开始步骤
```

关键点不是 `Promise.all` 本身，而是把所有可逆的观察和同意放在第一个副作用之前。命令系统不提供事务回滚；如果多条命令之间要求真正原子性，应由目标插件提供一个原子服务端命令，再由 Function 调用它。

## JavaScript 沙箱和运行预算

每次调用使用独立 Context，关闭 Host access、IO、环境变量、进程、线程、native access、Polyglot、动态 eval，以及 `load`、`print`、`console`。ECMAScript 目标为 2025，并要求 strict mode 语义。

发行默认允许单段同步代码运行 1 秒，整个异步工作流 300 秒；另有限制源码、操作数、并发、待审批、结果字符、深度和成员数。提高这些值会扩大资源占用和拒绝服务窗口，不会增加业务权限。

## 发布前检查扩展

1. `/mineclaw tools validate`：确认 handler、payload 和开关。
2. `/mineclaw functions validate`：确认 Schema、源码、capability、重复名称和 Skill 引用。
3. 用拒绝、超时、玩家离线、命令不存在、控制台异常和取消逐条演练。
4. 检查所有玩家名都来自运行时身份或准确在线名单，不从自由文本直接拼接。
5. 检查第一个副作用之前完成所有必要确认。
6. 检查返回值保留原始错误和必要上下文，并且 Skill 没有教模型过度宣称。
7. 最后再启用条目；活动 Turn 会继续使用其开始时的不可变快照。
