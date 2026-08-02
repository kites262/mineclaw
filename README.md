<div align="center">

# ⛏️ Mineclaw

**Give your Minecraft server an agent—not just a chatbot.**<br>
**给 Minecraft 服务器一个真正会理解、会查找、会行动的 Agent。**

Workspace-driven AI agents for Paper and Folia servers.<br>
面向 Paper 与 Folia 服务器、由工作区驱动的 AI Agent 体验。

<p>
  <a href="https://github.com/kites262/mineclaw/releases/tag/0.1.0"><img alt="Mineclaw 0.1.0" src="https://img.shields.io/badge/Mineclaw-0.1.0-4c8bf5"></a>
  <img alt="Minecraft 26.2" src="https://img.shields.io/badge/Minecraft-26.2-62b47a?logo=minecraft">
  <img alt="Paper and Folia native" src="https://img.shields.io/badge/Paper%20%2F%20Folia-native-efc75e">
  <img alt="Java 25" src="https://img.shields.io/badge/Java-25-e76f00?logo=openjdk">
  <img alt="OpenAI-compatible API" src="https://img.shields.io/badge/API-OpenAI--compatible-412991">
  <a href="LICENSE"><img alt="Apache License 2.0" src="https://img.shields.io/badge/License-Apache--2.0-8b5cf6"></a>
</p>

<p>
  <a href="https://github.com/kites262/mineclaw/releases/latest"><strong>Download</strong></a>
  ·
  <a href="https://github.com/kites262/mineclaw"><strong>Source</strong></a>
</p>

</div>

---

## ✨ Why Mineclaw

**为什么是 Mineclaw**

Mineclaw brings an agent-native experience into Minecraft. The model can understand operator-authored knowledge, inspect the current player's environment, discover skills, compose tools, and request real server actions—all from public chat.<br>
Mineclaw 把 Agent 级体验带进 Minecraft：模型能理解服主编写的知识、感知当前玩家环境、自主发现 Skill、组合 Tool，并从公共聊天中请求真实的服务器操作。

- **A workspace that shapes the agent.** `AGENTS.md`, `tools.yml`, and `skills/*.md` define its identity, knowledge, procedures, and available capabilities.
  <br>**用工作区塑造 Agent。** `AGENTS.md`、`tools.yml` 与 `skills/*.md` 共同定义身份、知识、操作规程和可用能力。
- **Context from the game, not guesses.** Read-only tools expose the block in sight, the block underfoot, a redacted inventory summary, and online account names.
  <br>**从游戏里拿上下文，而不是靠猜。** 只读 Tool 可以观察准星方块、脚下方块、脱敏背包摘要和在线玩家账号名。
- **Skills that turn server knowledge into play.** Rules, events, custom plugin commands, and multi-step procedures become documents the model can discover and follow.
  <br>**让服务器知识变成可玩的 Skill。** 服规、活动、第三方插件命令和多步流程都能写成模型可发现、可遵循的文档。
- **Visible, native interaction.** Streaming output appears in the Action Bar; the completed answer is broadcast once in public chat.
  <br>**自然、可见的服内交互。** 流式生成显示在 Action Bar，完整答案只在公屏广播一次。
- **Built for Paper and Folia.** Entity, region, global, HTTP, and file work stay on their appropriate scheduling boundaries.
  <br>**面向 Paper 与 Folia 构建。** 实体、区域、全局、HTTP 和文件任务各自在正确的调度边界运行。

## 🎮 Four ways to play with it

**四个具体玩法**

### 1. A context-aware survival companion

**随身环境助手**

A player asks: `@ai What am I standing on, and do I still have wood?` Mineclaw can combine `feet_block` and `inventory` to answer from the player's actual context without changing the world or inventory.<br>
玩家问：`@ai 我脚下是什么，背包里还有木头吗？` Mineclaw 可以组合 `feet_block` 与 `inventory`，根据玩家当下的真实环境回答，同时不修改世界和物品栏。

### 2. A server handbook that talks back

**会说话的服务器手册**

An operator documents event rules, claims, economies, or progression in the workspace. When a player asks `@ai How do I join the weekend event?`, the agent searches with `list`, `grep`, and `read`, then answers from the real documents instead of inventing policy.<br>
服主把活动、领地、经济或成长规则写进工作区。玩家问 `@ai 周末活动怎么参加？` 时，Agent 会用 `list`、`grep` 和 `read` 查找真实文档，而不是凭空编造玩法。

Workspace documents and skills are read hot, so a newly announced event can become queryable without restarting the server.<br>
工作区文档与 Skill 会热读取，因此新活动写入文档后通常无需重启，就能立刻被玩家问到。

### 3. A structure scout that understands uncertainty

**不会冒充成功的结构向导**

After command dispatch is enabled, a player can ask `@ai Where is the nearest End City?` The bundled `locate-structure` skill guides the agent to dispatch `locate structure end_city` as that player. Minecraft shows the coordinates on the player's screen; Mineclaw reports only what it can prove.<br>
启用命令分发后，玩家可以问 `@ai 最近的末地城在哪？` 内置 `locate-structure` Skill 会指导 Agent 以该玩家身份提交 `locate structure end_city`。坐标由 Minecraft 显示给玩家，Mineclaw 只报告它能确认的结果。

### 4. A custom plugin becomes an agent skill

**把第三方插件变成 Agent Skill**

Suppose a server already uses KitesPlaces. An operator can review the repository's [`kp-warps.md`](examples/skills/kp-warps.md) example, copy it into the workspace, and add narrowly scoped command rules. The agent can then discover real warp names, distinguish list/set/teleport semantics, and ask an explicitly named player to approve a cross-player request.<br>
假设服务器已经安装 KitesPlaces，服主可以审核仓库中的 [`kp-warps.md`](examples/skills/kp-warps.md) 示例，将它复制进工作区，并添加最小范围的命令规则。之后 Agent 就能查询真实传送点、区分 list/set/teleport 语义，并在跨玩家请求中让被指定玩家本人确认。

Mineclaw does not bundle KitesPlaces or silently gain new Java handlers from Markdown. The case demonstrates how existing tools, server commands, and operator-authored procedures can be composed into a new experience.<br>
Mineclaw 不会捆绑 KitesPlaces，Markdown 也不能凭空生成 Java handler。这个案例展示的是：现有 Tool、服务器命令与服主规程可以组合成新的玩法体验。

## 🧩 Compatibility

**兼容性**

- **Server:** Paper 26.2 or Folia 26.2; the descriptor declares `folia-supported: true`.
  <br>**服务端：** Paper 26.2 或 Folia 26.2；插件描述声明 `folia-supported: true`。
- **Runtime:** Java 25.
  <br>**运行时：** Java 25。
- **Model API:** a complete OpenAI-compatible Chat Completions endpoint. Tool use requires streaming and tool-call support from the provider.
  <br>**模型接口：** 完整的 OpenAI-compatible Chat Completions 端点；Tool 模式要求上游支持流式输出与 tool calls。
- **Other platforms:** standalone Spigot/Bukkit and other Paper or Minecraft versions are not compatibility promises.
  <br>**其他平台：** 不承诺兼容独立 Spigot/Bukkit 或其他 Paper、Minecraft 版本。

Treat `26.2 + Java 25` as the current target, not a minimum-version declaration.<br>
请把 `26.2 + Java 25` 视为当前适配目标，而不是最低版本。

## 📦 Installation

**安装**

1. Prepare a Paper/Folia 26.2 server running Java 25.<br>准备运行 Java 25 的 Paper/Folia 26.2 服务端。
2. Download `Mineclaw-0.1.0.jar` from [GitHub Releases](https://github.com/kites262/mineclaw/releases/latest), or build it from source.<br>从 [GitHub Releases](https://github.com/kites262/mineclaw/releases/latest) 下载 `Mineclaw-0.1.0.jar`，或从源码构建。
3. Stop the server and place the JAR in `plugins/`.<br>停止服务端，把 JAR 放入 `plugins/`。
4. Start once so Mineclaw can create its default workspace.<br>启动一次，让 Mineclaw 创建默认工作区。
5. Put the API key in `plugins/Mineclaw/.env`, then adjust `config.yml` as needed.<br>把 API 密钥写入 `plugins/Mineclaw/.env`，再按需调整 `config.yml`。
6. Restart, or run `/mineclaw reload` as an administrator.<br>重启服务端，或由管理员执行 `/mineclaw reload`。

Build from source:<br>
从源码构建：

```bash
git clone https://github.com/kites262/mineclaw.git
cd mineclaw
git checkout 0.1.0
./gradlew --no-daemon clean test assemblePlugin
```

Deployable artifact:<br>
可部署产物：

```text
build/plugins/Mineclaw-0.1.0.jar
```

First-start data directory:<br>
首次启动生成目录：

```text
plugins/Mineclaw/
├── config.yml
├── .env
├── message.yml
├── AGENTS.md
├── tools.yml
└── skills/
    ├── guide.md
    ├── command-safety.md
    └── locate-structure.md
```

## 🔐 API configuration and `.env`

**API 配置与 `.env`**

`api.base_url`, `api.model`, and `api.api_key` may each contain a literal value or the name of an environment variable. Mineclaw resolves each field in this order:<br>
`api.base_url`、`api.model` 与 `api.api_key` 均可填写字面量或环境变量名。Mineclaw 对每个字段按以下顺序解析：

**Process environment → sibling `.env` → literal text in `config.yml`**<br>
**系统环境变量 → 同目录 `.env` → `config.yml` 中的字面量**

Once a higher-priority layer defines a variable—even as an empty value—fallback stops. Only a missing variable name causes the configuration text itself to be used literally.<br>
只要更高优先级定义了该变量，即使值为空，也会终止回退；只有变量名不存在时，配置文本本身才作为字面量使用。

Recommended references:<br>
推荐写法：

```yaml
api:
  base_url: 'MINECLAW_API_BASE_URL'
  model: 'MINECLAW_API_MODEL'
  api_key: 'MINECLAW_API_KEY'
```

```dotenv
MINECLAW_API_BASE_URL=https://api.example.com/v1/chat/completions
MINECLAW_API_MODEL=your-model-id
MINECLAW_API_KEY=replace-with-your-secret
```

- The shipped configuration keeps the default URL and model as literals and points only the key at `MINECLAW_API_KEY`.
  <br>发行配置保留 URL 与模型字面量，只让密钥引用 `MINECLAW_API_KEY`。
- First start creates only an empty `MINECLAW_API_KEY=` placeholder and best-effort applies mode `0600` on POSIX filesystems.
  <br>首次启动只创建空的 `MINECLAW_API_KEY=` 占位符，并在 POSIX 文件系统上尽力设置为 `0600`。
- `.env` supports comments, `export`, single quotes, and double quotes, but performs no variable interpolation.
  <br>`.env` 支持注释、`export`、单双引号，但不执行变量插值。
- `.env` must be a regular, non-symlink UTF-8 file no larger than 64 KiB.
  <br>`.env` 必须是非符号链接的普通 UTF-8 文件，最大 64 KiB。
- The URL must be an absolute HTTP(S) URI; the model cannot be blank or contain whitespace/control characters. An empty key is rejected before any request.
  <br>URL 必须是绝对 HTTP(S) 地址；模型不能为空，也不能含空白或控制字符。空密钥会在请求发出前被本地拒绝。
- Legacy `api_key: ''` with `api_key_env` remains supported; `api_key_env` never falls back to a literal secret.
  <br>旧式 `api_key: ''` 配合 `api_key_env` 仍受支持；`api_key_env` 不会回退为字面密钥。

`config.yml` and `.env` are published as one immutable snapshot at startup or reload. A turn already in progress keeps its original snapshot.<br>
`config.yml` 与 `.env` 会在启动或重载时组成同一个不可变快照；已经开始的 turn 继续使用原快照。

See [the complete default configuration](src/main/resources/config.yml).<br>
查看[完整默认配置](src/main/resources/config.yml)。

## 🗂️ Workspace and extension model

**工作区与扩展方式**

- **`AGENTS.md`** defines the agent's identity, voice, priorities, operating procedure, and behavioral boundaries.
  <br>**`AGENTS.md`** 定义 Agent 的身份、语气、优先级、操作流程与行为边界。
- **`tools.yml`** enables, disables, and describes handlers implemented by the current Mineclaw build.
  <br>**`tools.yml`** 启用、停用并描述当前 Mineclaw 版本已经实现的 handler。
- **`skills/*.md`** documents server knowledge and teaches the model how to compose existing tools and commands.
  <br>**`skills/*.md`** 保存服务器知识，并教模型如何组合现有 Tool 与命令。
- **`message.yml`** controls player-facing messages, approval cards, and interaction hints.
  <br>**`message.yml`** 控制玩家可见文案、审批卡和交互提示。

`AGENTS.md`, `tools.yml`, `message.yml`, and skills are read hot when used. Editing them usually requires no restart. `config.yml` and `.env` take effect only at startup or after `/mineclaw reload`.<br>
`AGENTS.md`、`tools.yml`、`message.yml` 与 Skill 会在使用时热读取，修改后通常无需重启；`config.yml` 与 `.env` 只在启动或 `/mineclaw reload` 后生效。

Default seeding fills in missing files and never overwrites an existing agent, tool catalog, or skill. Markdown can teach the agent new procedures, but it cannot create a Java handler that Mineclaw does not implement or install a plugin absent from the server.<br>
默认播种只补齐缺失文件，不覆盖已有的 Agent、Tool 目录或 Skill。Markdown 可以教 Agent 新流程，但不能凭空创造 Mineclaw 尚未实现的 Java handler，也不能安装服务器上不存在的插件。

Bundled workspace resources:<br>
内置工作区资源：

- [AGENTS.md](src/main/resources/AGENTS.md)
- [tools.yml](src/main/resources/tools.yml)
- [message.yml](src/main/resources/message.yml)
- [skills/](src/main/resources/skills)

## 🧰 Built-in tools

**内置 Tool**

- `look_block` — reads the block the current player is looking at.<br>读取当前玩家准星指向的方块。
- `feet_block` — reads the block under the current player.<br>读取当前玩家脚下的方块。
- `inventory` — returns a redacted inventory summary.<br>返回脱敏的背包摘要。
- `online_players` — returns the current caller and online account names only.<br>只返回当前调用者与在线玩家账号名。
- `list`, `read`, `grep` — discover and read the Mineclaw workspace.<br>发现、搜索并读取 Mineclaw 工作区。
- `run_command` — requests a policy-checked command dispatch.<br>请求一次经过策略校验的命令分发。

`online_players` does not expose UUIDs, locations, worlds, permissions, or display names.<br>
`online_players` 不暴露 UUID、位置、世界、权限或展示名。

## 🛡️ Command policy and player approval

**命令策略与玩家审批**

Command execution is not the main source of Mineclaw's intelligence; it is an optional bridge from an agent decision to a server action. It starts disabled with `commands.run_enabled: false`.<br>
命令执行不是 Mineclaw 智能体验的主体，而是把 Agent 决策连接到服务器操作的可选桥梁；它默认通过 `commands.run_enabled: false` 关闭。

- Console commands must fully match `console_whitelist`.
  <br>控制台命令必须完整匹配 `console_whitelist`。
- A same-player command may dispatch directly only when the player is online and the normalized command fully matches `player_whitelist`.
  <br>当前玩家在线且规范化命令完整匹配 `player_whitelist` 时，才可能直接分发。
- Cross-player commands always require the actual target player's approval. Same-player commands outside the allowlist also enter approval.
  <br>跨玩家命令始终需要实际目标玩家确认；当前玩家未命中白名单的命令也会进入确认流程。
- Bukkit/Paper permissions still apply after Mineclaw policy accepts a request.
  <br>即使 Mineclaw 策略允许请求，Bukkit/Paper 权限仍然生效。

The approval card shows the requester, operation, command, execution identity, and expiry. Its final line contains clickable **Accept** and **Reject** buttons backed by one-time UUID tokens. Players never need to type an approve command.<br>
审批卡会展示请求者、操作内容、命令、执行身份与有效期，最后一行提供可点击的**接受**与**拒绝**按钮，并绑定一次性 UUID 令牌。玩家无需手输 approve 命令。

An optional gesture can accept a pending request: sneak, look straight up, and right-click air with a non-empty main-hand item that has no use effect in air. Empty-hand clicks and interactions that already do something—placing, throwing, eating, charging, blocking, tool use, or unknown custom behavior—are excluded.<br>
待确认时也可使用快捷手势：按住 Shift、视角朝正上方，并用主手中“对空气使用不会产生效果”的非空物品右键空气。空手以及放置、投掷、进食、蓄力、格挡、工具使用或未知自定义行为都不会触发。

**Dispatched does not mean succeeded.** Player dispatch confirms only that Bukkit accepted the command for dispatch; it cannot capture all player-facing feedback or prove side effects. Console dispatch may capture synchronous feedback, but that still does not prove every downstream effect completed.<br>
**分发成功不等于实际执行成功。** 玩家命令只能确认 Bukkit 接受了分发，无法捕获全部玩家反馈，也不能证明副作用完成；控制台命令可能捕获同步反馈，但仍不能证明所有后续效果已经完成。

Mineclaw distinguishes player offline, command not found, dispatch rejected, execution exception, and unknown outcome instead of collapsing them into success.<br>
Mineclaw 会区分玩家离线、命令未找到、分发被拒绝、执行异常与结果未知，不会把它们统一包装成成功。

## 🔒 Protected files and workspace boundary

**敏感文件与工作区边界**

- `config.yml` and `.env` may appear in listings, but expose only path, type, and `protected: true`—never size or contents.
  <br>`config.yml` 与 `.env` 可以出现在文件列表，但只暴露路径、类型和 `protected: true`，不返回大小或内容。
- `read` returns a fixed protected response; `grep` skips both files.
  <br>`read` 只返回固定保护提示，`grep` 会跳过这两个文件。
- Direct paths, normalized aliases, symlink aliases, and hard-link aliases receive the same protection.
  <br>直接路径、规范化别名、符号链接别名和硬链接别名都受同一保护。
- Absolute paths, `..` traversal, and symlink escape are rejected.
  <br>绝对路径、`..` 穿越和符号链接逃逸都会被拒绝。
- Current file tools are read-only: `list`, `read`, and `grep`. No edit, overwrite, move, or delete handler is exposed.
  <br>当前文件 Tool 只有只读的 `list`、`read` 与 `grep`，不提供编辑、覆盖、移动或删除 handler。

The model cannot elevate itself, edit protected configuration, read secrets, or bypass server permissions. AI is not OP; server policy remains authoritative.<br>
模型不能自行提权、编辑受保护配置、读取密钥或绕过服务器权限。AI 不是 OP，服务器策略始终拥有最终裁决权。

## 💬 Usage and permissions

**使用方式与权限**

- `@ai <question>` — starts a public AI turn. Permission: `mineclaw.command.chat` (default `true`).<br>发起一次公共 AI 对话。权限：`mineclaw.command.chat`（默认 `true`）。
- `/mineclaw clear` — clears the server-wide public session. Permission: `mineclaw.command.clear` (OP).<br>清空全服公共 Session。权限：`mineclaw.command.clear`（OP）。
- `/mineclaw reload` — atomically reloads `config.yml` and `.env`. Permission: `mineclaw.command.reload` (OP).<br>原子重载 `config.yml` 与 `.env`。权限：`mineclaw.command.reload`（OP）。
- `/mineclaw tools` — shows the current tool catalog status. Permission: `mineclaw.command.tools` (OP).<br>查看当前 Tool 目录状态。权限：`mineclaw.command.tools`（OP）。
- `mineclaw.command.approve` (default `true`) — receive and act on approvals addressed to the player.<br>接收并处理发给自己的审批。
- `mineclaw.bypass.ratelimit` (default `false`) — bypass the per-player rate limit.<br>绕过玩家级速率限制。

The public chat prefix defaults to `@ai` and can be changed with `chat.public_prefix`.<br>
公共聊天前缀默认为 `@ai`，可通过 `chat.public_prefix` 修改。

## 🖥️ Runtime behavior

**运行时行为**

- Only one AI turn runs server-wide at a time. Requests received while busy are rejected and never enter the session.
  <br>全服同一时间只运行一个 AI turn；忙碌时收到的请求会被拒绝，也不会进入 Session。
- The public session is server-wide and in-memory. It stores only completed user/assistant turns, not internal tool messages.
  <br>公共 Session 是全服共享的内存状态，只保存已完成的 user/assistant 轮次，不保存内部 Tool 消息。
- Restarting, disabling the plugin, or running `clear` removes the session.
  <br>重启、停用插件或执行 `clear` 会清空 Session。
- `context.max_messages` retains recent complete turns; reaching `context.max_tokens` clears the server session.
  <br>`context.max_messages` 保留最近的完整轮次；达到 `context.max_tokens` 时清空服务器 Session。
- In the Action Bar, one newline becomes a space or soft break. A blank line—two or more consecutive newlines—clears the current buffer and starts the next paragraph. CRLF and stream-chunk boundaries are normalized.
  <br>在 Action Bar 中，单个换行作为空格或软换行；空行（两个及以上连续换行）会清空当前缓冲并开始下一段，同时正确处理 CRLF 与跨流式分片边界。
- Safe Markdown rendering currently focuses on real `**bold**` formatting.
  <br>当前安全 Markdown 渲染重点支持真正的 `**粗体**`。

## 🔧 Configuration map

**配置速览**

- `api` — endpoint, key, model, timeout, and retries.<br>端点、密钥、模型、超时与重试。
- `context` — session message and token limits.<br>Session 消息与 token 上限。
- `chat` — public prefix, wake pattern, reply length, and Action Bar length.<br>公共前缀、唤醒规则、回复长度与 Action Bar 长度。
- `tools`, `commands` — tool switches, command dispatch, and allowlists.<br>Tool 开关、命令分发与白名单。
- `rate_limit` — per-player and global cooldowns.<br>玩家级与全局冷却。
- `workspace`, `file_tools` — default seeding and read-only file limits.<br>默认资源播种与只读文件限制。
- `turn` — maximum tool rounds and calls per turn.<br>每个 turn 的最大 Tool 轮次与调用数。
- `identity`, `environment` — fallback name, observation distance, inventory summary, and tool cooldown.<br>回退名称、观察距离、背包摘要与 Tool 冷却。
- `logging` — plugin log level.<br>插件日志级别。

Command regexes use full-match semantics. Scope every pattern deliberately: a broad expression grants broad dispatch authority.<br>
命令正则采用完整匹配语义。请有意识地限定每条表达式；过宽的规则等同于授予过宽的分发能力。

## 🧪 Build and verification

**构建与验证**

The project uses Gradle Wrapper 9.5.0, Java toolchain 25, and Paper API `26.2.build.87-stable`. `assemblePlugin` produces a deployable JAR containing runtime dependencies.<br>
项目使用 Gradle Wrapper 9.5.0、Java toolchain 25 与 Paper API `26.2.build.87-stable`；`assemblePlugin` 会生成包含运行时依赖的可部署 JAR。

```bash
./gradlew --no-daemon clean test assemblePlugin
```

JVM tests cover parsing, policy, rendering, protected files, and dispatch semantics. They cannot prove real Folia scheduler behavior or compatibility with every external plugin; verify high-risk changes on an actual 26.2 server.<br>
JVM 测试覆盖解析、策略、渲染、敏感文件保护与分发语义，但无法证明真实 Folia 调度行为或所有外部插件兼容性；高风险改动仍应在实际 26.2 服务端验证。

## ⚠️ Deliberate boundaries

**有意保留的边界**

- Mineclaw does not grant the model OP or replace Bukkit/Paper permission checks.<br>Mineclaw 不授予模型 OP，也不代替 Bukkit/Paper 权限检查。
- Enabling tools does not implicitly enable command dispatch.<br>启用 Tool 不会隐式开启命令分发。
- Approval authorizes one dispatch attempt, not proof of its outcome.<br>接受审批只授权一次分发尝试，不代表操作已经完成。
- Player-facing command feedback is not automatically returned to the model.<br>玩家屏幕上的命令反馈不会自动回传给模型。
- The gesture shortcut does not support an empty hand; clickable buttons remain the primary path.<br>快捷审批不支持空手；可点击按钮仍是主要交互。
- A Markdown skill cannot create a missing tool handler or install an absent server plugin.<br>Markdown Skill 不能创造不存在的 Tool handler，也不能安装服务器上没有的插件。
- The current conversation is public, server-wide, in-memory, and single-turn-at-a-time—not a private agent or persistent memory.<br>当前对话是全服公共、内存态、单并发，不是私聊 Agent 或持久记忆。

---

<div align="center">

**Build the agent your server deserves.**<br>
**为你的 Minecraft 服务器，塑造一个真正属于它的 Agent。**

</div>
