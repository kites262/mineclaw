---
id: kp-warps
name: KitesPlaces 传送点
description: 使用 KitesPlaces（kp warp）列出、传送和设置命名传送点
triggers:
  - 传送点列表、有哪些 warp、传到某传送点
  - 请求让一名明确指定的在线玩家前往某传送点
  - 设置/创建/更新传送点、保存当前位置为传送点
  - 玩家说 go / a / new / l 等传送点口语
  - 回家、传到刷怪笼等命名点
---

# KitesPlaces 传送点

> **第三方集成示例：** 本文件仅适用于已安装并配置 KitesPlaces 的服务器，不会由 Mineclaw 写入默认 Workspace。使用前请将它复制到 Workspace 的 `skills/`，审核下方命令与权限，并把相应规则合并到 `whitelist.yml`。

可审核后合并的最小白名单示例：

```yaml
schema: 1
enabled: true
player:
  - '^kp warp (?:teleport|set) [^\s]+$'
console:
  - '^kp warp list$'
```

`player` 规则只会让当前对话玩家自己的匹配命令免于确认；指定其他玩家执行时，Mineclaw 仍会要求该玩家本人确认。

与原版结构定位无关：人为命名的传送点用本 Skill；「最近的末地城/要塞在哪」走 `locate-structure`。

执行身份（与 Workspace `AGENTS.md` 一致）：

- `kp warp set` → **只允许**使用当前对话玩家 `player`（依赖其位置/权限，且会覆盖传送点坐标）。
- `kp warp teleport` → 默认使用当前对话玩家；仅当请求明确指定另一名在线玩家前往某个命名传送点时，先用 `online_players` 核对准确账号名，再把该玩家填入 `player`。跨玩家分发必须等待目标玩家本人确认。
- `kp warp list` → **优先**控制台（`player: null`），以便 `run_command` 的 `feedback` 带回列表正文。

执行任何 `run_command` 前，先按 `AGENTS.md` 核对身份、白名单与结果语义。<br>
**本 Skill 必须整篇读完再下发**，尤其「允许的命令」「参数形状」「典型易错」三节；不要凭常见服务器习惯或玩家口语臆造命令。

`command` **只用**全称 `kp warp …`；玩家口语 `/go` `/a` `/new` `/l` 只当意图线索，**禁止**作为 `run_command` 的 command（别名不在白名单内）。

## 典型易错（传送）

**正确唯一写法：** `kp warp teleport <精确名>`（三段，中间必须有 `teleport`）。

| 错误 command（禁止） | 为何错 |
| --- | --- |
| `kp warp 刷怪笼` / `kp warp <name>` | 缺子命令 `teleport`；会变成用法错误，不是传送 |
| `warp 刷怪笼` / `/warp <name>` | 本示例没有授权 `warp` 根命令；也不在白名单 |
| `go 刷怪笼` / `/go <name>` | 玩家别名，不在 Mineclaw 白名单；不可通过 `run_command` 下发 |
| `kp teleport 刷怪笼` | 根命令是 `kp`，子树是 `warp teleport`，不能跳过 `warp` |
| `kp warp tp 刷怪笼` | 子命令全称是 `teleport`，不是 `tp` |

list / set 同理：**不要**写成 `kp list`、`kp set 家2`、`kp warp create 家2` 等。

## 允许的命令

| 意图 | command | 执行身份 |
| --- | --- | --- |
| 列出全部传送点 | `kp warp list` | **优先** `player: null`（控制台） |
| 传送到某点 | `kp warp teleport <精确名>` | 对话玩家，或请求中明确指定并由其确认的在线玩家 |
| 在**当前位置**创建或更新传送点 | `kp warp set <名>` | 对话玩家 `player` |

参数形状（插件硬约束，多或少一个参数都会变成用法提示）：

- `list`：恰好两段 → `kp warp list`
- `teleport` / `set`：恰好三段 → `kp warp teleport|set <名>`
- `<名>` 必须是**一个**参数；名称里不能有空格

口语示例：

| 玩家说法 | 执行 |
| --- | --- |
| `/l`、有哪些传送点 | `kp warp list` |
| `/go 刷怪笼`、传到刷怪笼 | `kp warp teleport 刷怪笼`（名称须与 list 完全一致） |
| `/a`、回家（默认点） | `kp warp teleport 0000home` |
| `/new 家2`、把这里设为家2 | `kp warp set 家2` |

## 名称规则（写盘与 set 校验）

本示例所适配版本的 `WarpNames` 规则：

- 长度：**1–32 个 Unicode 码点**（按码点计，不是 Java `char` 数）
- 字符：每个码点必须是 **Unicode 字母**（`Character.isLetter`）或 **数字类字符**（`DECIMAL_DIGIT_NUMBER` / `LETTER_NUMBER` / `OTHER_NUMBER`）
- **区分大小写**；中文、拉丁字母、数字均可
- **禁止**：空格、下划线 `_`、连字符 `-`、标点、符号、控制字符

合法例：`刷怪笼`、`0000home`、`家2`、`上海Base42`<br>
非法例：`my_home`、`home-1`、`家 2`、空字符串、超过 32 个码点

`set` 会先校验名称再写盘；不合法时玩家侧收到插件错误，`run_command` 通常仍只会返回「已分发」。<br>
`teleport` **按精确键查找**（大小写与字形必须与 list 中的名称完全一致），不会做模糊匹配。

## 硬性规则

1. **list → 再传**：不确定精确名时，先 `kp warp list`（优先控制台），再按列表中的**完整名称**原样 `kp warp teleport`。模糊命中多个时列候选让玩家选，不要猜、不要改写名称。
2. **从 list feedback 抄名**：控制台 list 的 `feedback` 里会有名称；传送时**整段复制**该名称，不要省略前缀、不要「纠正」大小写或中英文混写（例：`冰川and海洋神殿` 必须全写）。
3. **没有真实「分类」字段**：玩家说「xx 类传送点」时，只对 list 结果按**名称语义**筛关键词；零匹配要明说。
4. **set 只写执行者脚下**：`kp warp set <名>` 把**该玩家当前坐标/朝向**存为该名；**同名直接覆盖**（CREATED/UPDATED/UNCHANGED 由插件判定）。执行前确认：名称合法、玩家意图就是改自己当前位置。
5. **set 权限**：`kitesplaces.command.warp.set` 通常只授予管理员；普通玩家可能无权限。玩家分发通常看不到「无权限」文案——`dispatched` ≠ 已创建。不要对无权限玩家反复调用 `set`。
6. **禁止**经本 Skill 执行：`kp warp delete`、`kp warp rename`、`kp config` 及任何未列出的 `kp` 子命令（不在白名单/未授权）。
7. **禁止**把结构 id 当传送点：`end_city` 等不是 warp 名。「传到末地城」若 list 无同名点 → 走 `locate-structure`，或说明需先到场 `set`。
8. **禁止** console 代跑 set/teleport（白名单与语义均不允许）；**允许** console 只跑 `kp warp list`。
9. **结果语义**：set/teleport 的 `dispatched` + `execution_result: unknown` 只表示已提交；玩家分发**不回传**游戏内反馈，不得声称「已传到/已设好」。未在 list 中确认存在的名字，传送后不要伪造成功。list 在控制台时，可如实转述 `feedback` 中的列表。
10. **默认家**：仅当玩家明确「回家 /a / 默认家」且未指定其他点时，用 `0000home`；不要把任意「家」自动改成别的已存在名。
11. **跨玩家仅限明确的 teleport 请求**：必须同时明确目标玩家和传送点；先核对在线账号名，再让目标玩家通过 Mineclaw 审批。不得替其他玩家执行 `set`，也不得把模糊的“拉人/找人”请求改写成跨玩家命令。

## 执行示例

- 「现在有哪些传送点」→ `command: "kp warp list"`，`player: null`，`intent: "列出传送点"`
- 「传到刷怪笼」→ 先 list 确认列表中确有 `刷怪笼`，再 `command: "kp warp teleport 刷怪笼"`（对话玩家）
- 「把这里设为家2」→ 名称合法后 `command: "kp warp set 家2"`（对话玩家）；若 list 已有同名，可一句提醒将覆盖该点坐标
- 「回家」且语境是默认点 → `command: "kp warp teleport 0000home"`（对话玩家）
- Bob 明确请求「让 Alice 去刷怪笼」→ 先核对在线玩家与传送点；若准确账号名为 `Alice` 且列表确有 `刷怪笼`，使用 `command: "kp warp teleport 刷怪笼"`、`player: "Alice"`。等待 Alice 本人确认，不能代为接受

## 边界

- 生成结构坐标 → `locate-structure`，不要 `kp warp teleport end_city`。
- 本示例只支持让明确指定的玩家前往一个 KitesPlaces 命名传送点；玩家坐标间直传、拉人或跟随不在此能力范围内。
- 删除/改名/改插件配置 → 本 Skill 不支持，说明需管理员手动处理。
