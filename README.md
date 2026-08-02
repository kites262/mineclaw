<div align="center">

# ⛏️ Mineclaw

**让服务器知识开口说话，让危险操作停在玩家指尖。 · Let server knowledge speak—and keep dangerous actions under player control.**

面向 Paper / Folia 的服内 AI 执行框架 · A Folia-native in-game AI harness for Paper servers

<p>
  <a href="https://github.com/kites262/mineclaw/releases/tag/0.1.0"><img alt="Mineclaw 0.1.0" src="https://img.shields.io/badge/Mineclaw-0.1.0-4c8bf5"></a>
  <img alt="Minecraft 26.2" src="https://img.shields.io/badge/Minecraft-26.2-62b47a?logo=minecraft">
  <img alt="Paper and Folia native" src="https://img.shields.io/badge/Paper%20%2F%20Folia-native-efc75e">
  <img alt="Java 25" src="https://img.shields.io/badge/Java-25-e76f00?logo=openjdk">
  <img alt="OpenAI-compatible API" src="https://img.shields.io/badge/API-OpenAI--compatible-412991">
  <a href="LICENSE"><img alt="Apache License 2.0" src="https://img.shields.io/badge/License-Apache--2.0-8b5cf6"></a>
</p>

<p>
  <a href="https://github.com/kites262/mineclaw/releases/latest"><strong>Download · 下载</strong></a>
  ·
  <a href="https://github.com/kites262/mineclaw"><strong>Source · 源码</strong></a>
</p>

</div>

---

## ✨ Why Mineclaw · 为什么是 Mineclaw

<table>
<thead>
<tr>
<th width="50%">简体中文</th>
<th width="50%">English</th>
</tr>
</thead>
<tbody>
<tr>
<td valign="top">
<p>Mineclaw 不是把一个聊天机器人塞进游戏。它把服务器维护者编写的规则、技能和工具接入模型，让玩家在聊天中查询环境、查阅服规，并在明确的策略边界内请求命令。</p>
<ul>
<li><strong>工作区驱动：</strong><code>AGENTS.md</code>、<code>tools.yml</code> 和 <code>skills/*.md</code> 决定 AI 能知道什么、怎样行动。</li>
<li><strong>原生 Folia 调度：</strong>玩家、区域、全局和异步工作按正确的调度边界执行。</li>
<li><strong>可见的流式体验：</strong>生成过程显示在 Action Bar，最终答案只广播一次。</li>
<li><strong>默认拒绝危险能力：</strong>命令执行默认关闭；启用后仍受白名单、目标玩家审批和一次性令牌约束。</li>
</ul>
</td>
<td valign="top">
<p>Mineclaw is not merely a chatbot placed inside Minecraft. It connects an AI model to operator-authored rules, skills, and narrowly scoped tools, so players can inspect their surroundings, consult server knowledge, and request commands within explicit policy boundaries.</p>
<ul>
<li><strong>Workspace-driven:</strong> <code>AGENTS.md</code>, <code>tools.yml</code>, and <code>skills/*.md</code> define what the AI knows and how it may act.</li>
<li><strong>Folia-native scheduling:</strong> player, region, global, and asynchronous work stay on the correct scheduler boundaries.</li>
<li><strong>Visible streaming:</strong> progress appears in the Action Bar and the final answer is broadcast exactly once.</li>
<li><strong>Dangerous capabilities are denied by default:</strong> command execution starts disabled and remains constrained by allowlists, target-player approval, and one-time tokens when enabled.</li>
</ul>
</td>
</tr>
</tbody>
</table>

<table>
<tbody>
<tr>
<td width="50%" valign="top">
<strong>重要：AI 不是 OP。</strong><br>
模型不能自行提升权限、修改插件配置、读取密钥或绕过审批。它只能调用管理员显式启用的内置工具；服务器权限系统仍是最终裁决者。
</td>
<td width="50%" valign="top">
<strong>Important: AI is not OP.</strong><br>
The model cannot elevate itself, edit plugin configuration, read secrets, or bypass approval. It can only call built-in tools explicitly enabled by an operator, and the server permission system remains authoritative.
</td>
</tr>
</tbody>
</table>

## 🎮 Four concrete cases · 四个具体场景

<table>
<thead>
<tr>
<th width="50%">简体中文</th>
<th width="50%">English</th>
</tr>
</thead>
<tbody>
<tr>
<td valign="top">
<h3>1. 随身环境助手</h3>
<p>玩家问：<code>@ai 我脚下是什么，背包里还有木头吗？</code></p>
<p>Mineclaw 可以调用只读的 <code>feet_block</code> 和 <code>inventory</code> 工具，返回脚下方块与经过脱敏的物品摘要。它不需要 OP，也不会修改世界或物品栏。</p>
</td>
<td valign="top">
<h3>1. A contextual survival helper</h3>
<p>A player asks: <code>@ai What am I standing on, and do I still have wood?</code></p>
<p>Mineclaw can call the read-only <code>feet_block</code> and <code>inventory</code> tools and return the block plus a redacted inventory summary. No OP access is required, and neither the world nor the inventory is modified.</p>
</td>
</tr>
<tr>
<td valign="top">
<h3>2. 会说话的服务器手册</h3>
<p>维护者把传送、领地或活动规则写进工作区文档，玩家再问：<code>@ai 这个服怎么参加周末活动？</code></p>
<p>AI 使用 <code>list</code>、<code>grep</code> 和 <code>read</code> 从真实文档中找答案；找不到时应明确说不知道，而不是编造规则。文档和 Skill 可热读取，通常无需重启。</p>
</td>
<td valign="top">
<h3>2. A server handbook that talks back</h3>
<p>An operator documents warps, claims, or event rules in the workspace. A player then asks: <code>@ai How do I join the weekend event?</code></p>
<p>The AI uses <code>list</code>, <code>grep</code>, and <code>read</code> to answer from real documents. If nothing is documented, it should say so instead of inventing policy. Documents and skills are read hot, so a restart is usually unnecessary.</p>
</td>
</tr>
<tr>
<td valign="top">
<h3>3. 查找末地城，但不冒充成功</h3>
<p>在管理员启用命令工具且权限允许时，玩家问：<code>@ai 最近的末地城在哪？</code></p>
<p>内置 <code>locate-structure</code> Skill 会指导 AI 以当前玩家身份分发 <code>locate structure end_city</code>。坐标由 Minecraft 显示给玩家；AI 只报告“命令已分发”，不会把分发成功误报成已经找到或到达目标。</p>
</td>
<td valign="top">
<h3>3. Locate an End City without pretending it succeeded</h3>
<p>After an operator enables command tools and the player has the required permission, they ask: <code>@ai Where is the nearest End City?</code></p>
<p>The bundled <code>locate-structure</code> skill guides the AI to dispatch <code>locate structure end_city</code> as the current player. Minecraft shows the coordinates to that player; the AI reports only that the command was dispatched, never that the structure was found or reached.</p>
</td>
</tr>
<tr>
<td valign="top">
<h3>4. 自定义 Skill 的跨玩家传送审批</h3>
<p>假设服务器确实安装了 KitesPlaces：维护者可以自行编写 Skill，或审核并复制仓库中的 <a href="examples/skills/kp-warps.md"><code>kp-warps.md</code> 示例</a>，再为需要的命令配置精确白名单。Skill 可指导 AI 先查询真实传送点名；若 Bob 请求让 Alice 执行传送，Alice 会收到包含命令、请求者和有效期的审批卡。</p>
<p>Alice 可以点击接受或拒绝；AI 无法替她批准。接受后也只表示开始分发命令，不代表传送副作用一定完成。Mineclaw 默认不附带 KitesPlaces 专用能力，也不会捆绑或安装该插件。</p>
</td>
<td valign="top">
<h3>4. Cross-player warp approval through a custom skill</h3>
<p>Suppose KitesPlaces is actually installed on the server. An operator can write a custom skill, or review and copy the repository's <a href="examples/skills/kp-warps.md"><code>kp-warps.md</code> example</a>, then configure precise allowlist entries for the required commands. The skill may guide the AI to query real warp names first; if Bob asks Alice to run a teleport, Alice receives an approval card containing the command, requester, and expiry.</p>
<p>Alice can click Accept or Reject; the AI cannot approve on her behalf. Acceptance only starts command dispatch and does not prove the teleport side effect completed. Mineclaw ships no KitesPlaces-specific capability by default and neither bundles nor installs that plugin.</p>
</td>
</tr>
</tbody>
</table>

## 🧩 Compatibility · 兼容性

| Layer / 层 | 简体中文 | English |
|---|---|---|
| Server / 服务端 | 面向 **Paper 26.2** 与 **Folia 26.2**；插件描述声明 `folia-supported: true`。 | Targets **Paper 26.2** and **Folia 26.2**; the plugin descriptor declares `folia-supported: true`. |
| Runtime / 运行时 | **Java 25**。 | **Java 25**. |
| Model API / 模型接口 | OpenAI-compatible Chat Completions 完整端点；工具模式需要服务端支持流式输出与 tool calls。 | A full OpenAI-compatible Chat Completions endpoint; tool mode requires streaming and tool-call support from the provider. |
| Optional integration / 可选集成 | 可为服务器已安装的第三方插件编写自定义 Skill，并同时配置最小化命令白名单；Mineclaw 不附带插件专用集成。 | Operators may write custom skills for third-party plugins already installed on the server and pair them with minimal command allowlists; Mineclaw bundles no plugin-specific integration. |
| Other platforms / 其他平台 | 未承诺兼容独立 Spigot/Bukkit 或其他 Minecraft/Paper 版本。 | Standalone Spigot/Bukkit and other Minecraft/Paper versions are not supported promises. |

| 简体中文 | English |
|---|---|
| Mineclaw 对调度和协议版本较敏感。请把 `26.2 + Java 25` 视为当前适配目标，而不是最低版本。 | Mineclaw is sensitive to scheduler and protocol changes. Treat `26.2 + Java 25` as the current target, not a minimum-version declaration. |

## 📦 Installation · 安装

<table>
<thead>
<tr>
<th width="50%">简体中文</th>
<th width="50%">English</th>
</tr>
</thead>
<tbody>
<tr>
<td valign="top">
<ol>
<li>准备 Java 25 与 Paper/Folia 26.2 服务端。</li>
<li>从 <a href="https://github.com/kites262/mineclaw/releases/latest">GitHub Releases</a> 下载 <code>0.1.0</code> JAR，或从源码构建。</li>
<li>停止服务端，把 JAR 放入 <code>plugins/</code>。</li>
<li>启动一次，让 Mineclaw 创建默认资源。</li>
<li>把密钥写入 <code>plugins/Mineclaw/.env</code>，按需调整 <code>config.yml</code>。</li>
<li>重启，或由管理员执行 <code>/mineclaw reload</code>。</li>
</ol>
</td>
<td valign="top">
<ol>
<li>Prepare a Paper/Folia 26.2 server running Java 25.</li>
<li>Download the <code>0.1.0</code> JAR from <a href="https://github.com/kites262/mineclaw/releases/latest">GitHub Releases</a>, or build it from source.</li>
<li>Stop the server and place the JAR in <code>plugins/</code>.</li>
<li>Start once so Mineclaw can create its default resources.</li>
<li>Put the secret in <code>plugins/Mineclaw/.env</code> and adjust <code>config.yml</code> as needed.</li>
<li>Restart, or have an administrator run <code>/mineclaw reload</code>.</li>
</ol>
</td>
</tr>
</tbody>
</table>

Build from the repository / 从仓库构建：

```bash
git clone https://github.com/kites262/mineclaw.git
cd mineclaw
git checkout 0.1.0
./gradlew --no-daemon clean test assemblePlugin
```

The deployable artifact / 可部署产物：

```text
build/plugins/Mineclaw-0.1.0.jar
```

First-start data directory / 首次启动生成目录：

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

## 🔐 Secrets and `.env` · 密钥与 `.env`

<table>
<thead>
<tr>
<th width="50%">简体中文</th>
<th width="50%">English</th>
</tr>
</thead>
<tbody>
<tr>
<td valign="top">
<p><code>api.base_url</code>、<code>api.model</code> 和 <code>api.api_key</code> 都可以填写字面量，也可以填写环境变量名。Mineclaw 按以下顺序解析：</p>
<p><strong>系统环境变量 → 同目录 <code>.env</code> → <code>config.yml</code> 中的字面量</strong></p>
<p>如果某一层定义了该变量，即使值为空，也不会继续向下回退。环境变量名不存在时，配置值本身才会作为字面量使用。</p>
</td>
<td valign="top">
<p><code>api.base_url</code>, <code>api.model</code>, and <code>api.api_key</code> may each contain either a literal value or an environment-variable name. Mineclaw resolves them in this order:</p>
<p><strong>process environment → sibling <code>.env</code> → literal text in <code>config.yml</code></strong></p>
<p>Once a variable is defined at a higher-priority layer, even an empty value stops fallback. Only a missing variable name causes the configuration text itself to be used as a literal.</p>
</td>
</tr>
</tbody>
</table>

Recommended `config.yml` references / 推荐的 `config.yml` 引用：

```yaml
api:
  base_url: 'MINECLAW_API_BASE_URL'
  model: 'MINECLAW_API_MODEL'
  api_key: 'MINECLAW_API_KEY'
```

Corresponding `.env` / 对应的 `.env`：

```dotenv
MINECLAW_API_BASE_URL=https://api.example.com/v1/chat/completions
MINECLAW_API_MODEL=your-model-id
MINECLAW_API_KEY=replace-with-your-secret
```

<table>
<tbody>
<tr>
<td width="50%" valign="top">
<ul>
<li>默认配置把 URL 和模型写成字面量，只把密钥指向 <code>MINECLAW_API_KEY</code>。</li>
<li>首次启动只在 <code>.env</code> 中创建空的密钥占位符，并在 POSIX 文件系统上尽力设为 <code>0600</code>。</li>
<li><code>.env</code> 支持注释、<code>export</code>、单引号和双引号；不进行变量插值。</li>
<li><code>.env</code> 必须是非符号链接的普通 UTF-8 文件，最大 64 KiB。</li>
<li>URL 必须是绝对 HTTP(S) 地址；模型名不能为空、不能含空白或控制字符。空密钥会在本地拒绝请求。</li>
<li>旧式 <code>api_key: ''</code> 配合 <code>api_key_env</code> 仍受支持，但 <code>api_key_env</code> 没有字面量回退。</li>
</ul>
</td>
<td width="50%" valign="top">
<ul>
<li>The shipped configuration keeps the URL and model literal and points only the key at <code>MINECLAW_API_KEY</code>.</li>
<li>On first start, Mineclaw creates only an empty key placeholder in <code>.env</code> and best-effort applies mode <code>0600</code> on POSIX filesystems.</li>
<li><code>.env</code> supports comments, <code>export</code>, single quotes, and double quotes; variable interpolation is intentionally absent.</li>
<li><code>.env</code> must be a non-symlink, regular UTF-8 file no larger than 64 KiB.</li>
<li>The URL must be an absolute HTTP(S) URI; model names cannot be empty or contain whitespace/control characters. An empty key is rejected locally before a request is made.</li>
<li>Legacy <code>api_key: ''</code> with <code>api_key_env</code> remains supported, but <code>api_key_env</code> has no literal fallback.</li>
</ul>
</td>
</tr>
</tbody>
</table>

| 简体中文 | English |
|---|---|
| `config.yml` 与 `.env` 会在启动或重载时组成同一个不可变配置快照；已经开始的请求继续使用原快照，不会在每轮工具调用中重新读取密钥。 | Configuration and `.env` are loaded as one immutable snapshot at startup or reload. A request already in progress keeps its snapshot; the plugin does not re-read secrets during every tool round. |

See the complete defaults / 查看完整默认项：[src/main/resources/config.yml](src/main/resources/config.yml)

## 🗂️ Workspace and extension model · 工作区与扩展方式

<table>
<thead>
<tr>
<th width="50%">简体中文</th>
<th width="50%">English</th>
</tr>
</thead>
<tbody>
<tr>
<td valign="top">
<ul>
<li><code>AGENTS.md</code>：定义身份、语气、优先级和服务器级行为约束。</li>
<li><code>tools.yml</code>：启用或停用 Mineclaw 已实现的内置工具。</li>
<li><code>skills/*.md</code>：把服务器玩法、第三方插件命令和安全流程写成模型可遵循的手册。</li>
<li><code>message.yml</code>：玩家可见文案、审批卡与交互提示。</li>
</ul>
<p>固定资源会在使用时热读取，因此调整 AGENT、Skill、工具说明或文案通常无需重启。<code>config.yml</code> 与 <code>.env</code> 只在启动或 <code>/mineclaw reload</code> 时生效。</p>
</td>
<td valign="top">
<ul>
<li><code>AGENTS.md</code> defines identity, tone, priorities, and server-wide behavioral constraints.</li>
<li><code>tools.yml</code> enables or disables built-in tools that Mineclaw actually implements.</li>
<li><code>skills/*.md</code> turns gameplay rules, third-party plugin commands, and safety procedures into model-readable playbooks.</li>
<li><code>message.yml</code> controls player-facing copy, approval cards, and interaction hints.</li>
</ul>
<p>Fixed workspace resources are read hot when used, so AGENT, skill, tool-description, and message changes usually require no restart. <code>config.yml</code> and <code>.env</code> take effect only at startup or after <code>/mineclaw reload</code>.</p>
</td>
</tr>
<tr>
<td valign="top">
<p><strong>扩展边界：</strong>Markdown Skill 可以教 AI 如何组合现有工具和服务器已有命令，但不能凭空增加 Java 工具处理器，也不能安装第三方插件。<code>tools.yml</code> 只接受当前版本支持的 handler。</p>
<p>首次生成只补齐缺少的默认文件，不覆盖已有的 AGENT、工具或 Skill。升级后若想采用新版默认内容，请人工比较并合并。</p>
</td>
<td valign="top">
<p><strong>Extension boundary:</strong> Markdown skills can teach the AI how to combine existing tools and commands already present on the server, but they cannot create new Java tool handlers or install third-party plugins. <code>tools.yml</code> accepts only handlers supported by the current build.</p>
<p>Default seeding fills in missing files and does not overwrite an existing AGENT, tool catalog, or skill. To adopt newer defaults after an upgrade, compare and merge them deliberately.</p>
</td>
</tr>
</tbody>
</table>

Bundled references / 默认资源：

- [AGENTS.md](src/main/resources/AGENTS.md)
- [tools.yml](src/main/resources/tools.yml)
- [message.yml](src/main/resources/message.yml)
- [skills/](src/main/resources/skills)

## 🛡️ Command policy and approval · 命令策略与审批

| Stage / 阶段 | 简体中文 | English |
|---|---|---|
| Master switch / 总开关 | `commands.run_enabled` 默认为 `false`；关闭时全部命令请求被拒绝。 | `commands.run_enabled` defaults to `false`; all command requests are denied while it is off. |
| Normalization / 规范化 | 去掉一个前导 `/`、折叠空白、转为小写，再进行正则完整匹配。 | One leading `/` is removed, whitespace is collapsed, text is lowercased, and regexes must match the full command. |
| Console / 控制台 | 只有完整匹配 `console_whitelist` 才能分发。 | Dispatch is possible only after a full match against `console_whitelist`. |
| Current player / 当前玩家 | 当前对话玩家在线且完整匹配 `player_whitelist` 时，可直接尝试分发；Minecraft 权限仍然生效。 | If the speaking player is online and the command fully matches `player_whitelist`, dispatch may be attempted directly; Minecraft permissions still apply. |
| Approval / 审批 | 跨玩家命令始终审批；当前玩家命令未命中白名单时也进入审批。审批发给实际目标玩家，60 秒过期。 | Cross-player commands always require approval; same-player commands outside the allowlist do as well. The actual target player receives the request, which expires after 60 seconds. |
| Result / 结果 | 区分玩家离线、命令未找到、分发被拒绝、执行异常与结果未知。 | Results distinguish player offline, command not found, dispatch rejected, execution exception, and unknown outcome. |

<table>
<tbody>
<tr>
<td width="50%" valign="top">
<h3>审批卡</h3>
<p>审批消息包含请求者、操作说明、命令、目标玩家和过期时间，最后一行提供可点击的<strong>接受</strong>/<strong>拒绝</strong>按钮。按钮绑定一次性 UUID 令牌；已使用、过期或旧请求的令牌不能批准后续操作。</p>
<p>玩家不需要、也不应该手输 approve 命令。内部令牌命令只用于聊天组件的点击事件。</p>
</td>
<td width="50%" valign="top">
<h3>Approval card</h3>
<p>The card includes the requester, operation explanation, command, target player, and expiry. Its final line contains clickable <strong>Accept</strong>/<strong>Reject</strong> buttons. Each button carries a one-time UUID token; consumed, expired, or stale tokens cannot approve a later request.</p>
<p>Players do not need—and should not be asked—to type an approve command. Internal token commands exist only as chat-component click targets.</p>
</td>
</tr>
<tr>
<td valign="top">
<h3>可选快捷手势</h3>
<p>有待审批请求时，目标玩家可按住 Shift、视角朝正上方，并用<strong>非空、对空气使用不会产生效果</strong>的主手物品右键空气。空手不会触发。</p>
<p>只有纯粹无意义的右键空气才会接受：放置方块、扔鸡蛋、进食、拉弓、举盾、使用工具、自定义物品或其他本来会产生效果的交互都会被排除。事件只会在审批真正开始后被消费；点击按钮仍是最直观的方式。</p>
</td>
<td valign="top">
<h3>Optional shortcut gesture</h3>
<p>While an approval is pending, the target player may sneak, look straight up, and right-click air with a <strong>non-empty main-hand item that has no use effect in air</strong>. An empty hand does not trigger the shortcut.</p>
<p>Only a genuinely meaningless air click is accepted. Placing a block, throwing an egg, eating, drawing a bow, raising a shield, using a tool, activating a custom item, or any interaction that already has an effect is excluded. The event is consumed only after approval actually starts; the clickable button remains the clearest path.</p>
</td>
</tr>
</tbody>
</table>

| 简体中文 | English |
|---|---|
| **分发成功 ≠ 实际执行成功**<br>玩家命令只能确认 Bukkit 接受了分发，无法捕获全部玩家反馈，也无法证明副作用完成。控制台命令可能捕获同步反馈，但仍不能证明所有后续效果已经完成。 | **Dispatched ≠ succeeded**<br>Player dispatch can confirm only that Bukkit accepted the command for dispatch; it cannot capture all player-facing feedback or prove side effects. Console dispatch may capture synchronous feedback, but even that is not proof that every downstream effect completed. |

## 🔒 Protected files and workspace boundary · 敏感文件与工作区边界

<table>
<thead>
<tr>
<th width="50%">简体中文</th>
<th width="50%">English</th>
</tr>
</thead>
<tbody>
<tr>
<td valign="top">
<ul>
<li><code>config.yml</code> 与 <code>.env</code> 可以出现在文件列表，但只暴露路径、类型和 <code>protected</code> 标记，不返回大小或内容。</li>
<li><code>read</code> 对敏感文件只返回固定的 <code>protected</code> 提示；<code>grep</code> 会跳过它们。</li>
<li>直接路径、规范化别名、符号链接和硬链接别名都受同一保护。</li>
<li>工作区路径会进行规范化与真实路径校验；绝对路径、<code>..</code> 穿越和符号链接逃逸会被拒绝。</li>
<li>当前 AI 文件工具只有 <code>list</code>、<code>read</code> 和 <code>grep</code>，没有编辑、覆盖、移动或删除处理器。</li>
<li>API Key 与 Authorization 不应进入工具输出或普通日志。</li>
</ul>
</td>
<td valign="top">
<ul>
<li><code>config.yml</code> and <code>.env</code> may appear in file listings, but only their path, type, and <code>protected</code> marker are exposed—never size or contents.</li>
<li><code>read</code> returns a fixed <code>protected</code> response for sensitive files, while <code>grep</code> skips them.</li>
<li>Direct paths, normalized aliases, symlink aliases, and hard-link aliases receive the same protection.</li>
<li>Workspace paths are normalized and checked against real paths; absolute paths, <code>..</code> traversal, and symlink escape are rejected.</li>
<li>The current AI file tools are limited to <code>list</code>, <code>read</code>, and <code>grep</code>; no edit, overwrite, move, or delete handler is exposed.</li>
<li>API keys and Authorization values must not enter tool output or ordinary logs.</li>
</ul>
</td>
</tr>
</tbody>
</table>

| 简体中文 | English |
|---|---|
| `online_players` 同样遵循最少信息原则：只返回当前轮次调用者账号名和在线账号名，不返回 UUID、位置、世界、权限或展示名。 | The `online_players` tool follows the same least-information principle: it returns only the current caller account name and online account names—not UUIDs, locations, worlds, permissions, or display names. |

## 💬 Usage, commands, and permissions · 使用、命令与权限

| Entry / 入口 | Default permission / 默认权限 | 简体中文 | English |
|---|---:|---|---|
| `@ai <问题>` | `mineclaw.command.chat` (`true`) | 在公共聊天发起一次 AI 对话。前缀可通过 `chat.public_prefix` 修改。 | Starts an AI turn from public chat. Change the prefix with `chat.public_prefix`. |
| `/mineclaw clear` | `mineclaw.command.clear` (OP) | 清空服务器级公共会话上下文。 | Clears the server-wide public conversation context. |
| `/mineclaw reload` | `mineclaw.command.reload` (OP) | 原子重载 `config.yml` 与 `.env`；其他工作区资源按使用时热读取。 | Atomically reloads `config.yml` and `.env`; other workspace resources are read hot when used. |
| `/mineclaw tools` | `mineclaw.command.tools` (OP) | 查看当前已加载工具及其状态。 | Shows the currently loaded tools and their status. |

Additional permissions / 其他权限：

| Permission | Default | 简体中文 | English |
|---|---:|---|---|
| `mineclaw.command.chat` | `true` | 使用聊天入口。 | Use the public chat entry point. |
| `mineclaw.command.approve` | `true` | 接收并操作属于自己的审批。 | Receive and act on approvals addressed to the player. |
| `mineclaw.bypass.ratelimit` | `false` | 绕过玩家级速率限制。 | Bypass the per-player rate limit. |

## 🖥️ Runtime behavior · 运行时行为

<table>
<thead>
<tr>
<th width="50%">简体中文</th>
<th width="50%">English</th>
</tr>
</thead>
<tbody>
<tr>
<td valign="top">
<ul>
<li>全服同一时间只运行一个 AI turn；忙碌时的新请求会被拒绝，不会进入会话。</li>
<li>公共会话保存在内存中，只记录已完成的用户/助手轮次，不保留内部工具消息；重启、禁用插件或执行 <code>clear</code> 会清空。</li>
<li><code>context.max_messages</code> 保留最近的完整轮次；达到 <code>context.max_tokens</code> 时清空服务器会话。</li>
<li>流式文本显示于 Action Bar：单个换行作为空格/软换行；空行（两个或更多连续换行）清空当前缓冲并开始下一段。CRLF、连续换行和跨流分片都会正确归一化。</li>
<li>最终答案只在公共聊天广播一次。当前安全 Markdown 渲染重点支持 <strong>粗体</strong>。</li>
<li>玩家与全局速率限制、API 超时和重试均可配置。</li>
</ul>
</td>
<td valign="top">
<ul>
<li>Only one AI turn runs server-wide at a time. New requests while busy are rejected and do not enter the conversation.</li>
<li>The public session lives in memory and stores only completed user/assistant turns, not internal tool messages. Restarting, disabling the plugin, or running <code>clear</code> removes it.</li>
<li><code>context.max_messages</code> retains recent complete turns; reaching <code>context.max_tokens</code> clears the server session.</li>
<li>Streaming text appears in the Action Bar: one newline becomes a space/soft break, while a blank line (two or more consecutive newlines) clears the current buffer and starts the next paragraph. CRLF, repeated newlines, and boundaries across stream chunks are normalized correctly.</li>
<li>The final answer is broadcast once in public chat. Safe Markdown rendering currently focuses on real <strong>bold</strong> formatting.</li>
<li>Per-player/global rate limits, API timeout, and retries are configurable.</li>
</ul>
</td>
</tr>
</tbody>
</table>

## 🔧 Configuration map · 配置速览

| Section / 配置段 | 简体中文 | English |
|---|---|---|
| `api` | 端点、密钥、模型、超时与重试。 | Endpoint, key, model, timeout, and retry behavior. |
| `context` | 会话消息与 token 上限。 | Conversation message and token limits. |
| `chat` | 公共前缀、唤醒规则、聊天与 Action Bar 长度。 | Public prefix, wake pattern, chat length, and Action Bar length. |
| `tools` / `commands` | 工具总开关、禁用项、命令执行与白名单。 | Tool master switch, disabled tools, command dispatch, and allowlists. |
| `rate_limit` | 玩家级和全局冷却。 | Per-player and global cooldowns. |
| `workspace` / `file_tools` | 默认资源播种与只读文件工具限制。 | Default-resource seeding and read-only file-tool limits. |
| `turn` | 单轮最大工具轮次和调用数。 | Maximum tool rounds and calls per turn. |
| `identity` / `environment` | 助手名称、观察距离、物品栏摘要与工具冷却。 | Assistant name, observation distance, inventory summary, and tool cooldown. |
| `logging` | 插件日志级别。 | Plugin log level. |

| 简体中文 | English |
|---|---|
| 命令正则采用完整匹配语义。请有意识地限定每条表达式；过宽的规则等同于授予过宽的分发能力。 | For command regexes, Mineclaw uses full-match semantics. Anchor and scope every expression deliberately; broad patterns grant broad dispatch authority. |

## 🧪 Build and verification · 构建与验证

<table>
<thead>
<tr>
<th width="50%">简体中文</th>
<th width="50%">English</th>
</tr>
</thead>
<tbody>
<tr>
<td valign="top">
<p>项目使用 Gradle Wrapper 9.5.0、Java toolchain 25、Paper API <code>26.2.build.87-stable</code>。<code>assemblePlugin</code> 生成包含运行时依赖的可部署 JAR。</p>
<p>提交前建议至少执行完整测试与打包：</p>
</td>
<td valign="top">
<p>The project uses Gradle Wrapper 9.5.0, Java toolchain 25, and Paper API <code>26.2.build.87-stable</code>. <code>assemblePlugin</code> produces a deployable JAR containing runtime dependencies.</p>
<p>Before shipping a change, run at least the full test and packaging pipeline:</p>
</td>
</tr>
</tbody>
</table>

```bash
./gradlew --no-daemon clean test assemblePlugin
```

| 简体中文 | English |
|---|---|
| JVM 单元测试和集成式测试覆盖解析、策略、渲染、文件保护与分发语义，但无法证明真实 Folia 调度行为或所有外部插件兼容性；高风险改动仍应在实际 26.2 服务端验证。 | Unit and integration-style JVM tests cover parsing, policy, rendering, protection, and dispatch semantics. They cannot prove real Folia scheduler behavior or compatibility with every external plugin; verify high-risk changes on an actual 26.2 server. |

## ⚠️ Deliberate boundaries · 有意保留的边界

<table>
<thead>
<tr>
<th width="50%">简体中文</th>
<th width="50%">English</th>
</tr>
</thead>
<tbody>
<tr>
<td valign="top">
<ul>
<li>Mineclaw 不授予模型 OP，也不代替 Bukkit/Paper 权限检查。</li>
<li>默认不开启命令执行；启用工具不等于放开命令。</li>
<li>接受审批只授权一次分发尝试，不是结果证明。</li>
<li>玩家命令的屏幕反馈不会自动回传给 AI。</li>
<li>快捷审批不支持空手；聊天按钮是主要交互。</li>
<li>一个 Markdown Skill 不能实现仓库中不存在的工具或服务器上不存在的插件。</li>
<li>当前会话是全服公共、内存态、单并发，不是私聊代理或持久记忆。</li>
</ul>
</td>
<td valign="top">
<ul>
<li>Mineclaw never grants the model OP and does not replace Bukkit/Paper permission checks.</li>
<li>Command execution is off by default; enabling tools does not implicitly enable commands.</li>
<li>Approval authorizes one dispatch attempt, not proof of its outcome.</li>
<li>Player-facing command feedback is not automatically returned to the AI.</li>
<li>The gesture shortcut does not support an empty hand; chat buttons are the primary interaction.</li>
<li>A Markdown skill cannot create a tool missing from the codebase or a plugin absent from the server.</li>
<li>The current conversation is public, server-wide, in-memory, and single-turn-at-a-time—not a private agent or persistent memory.</li>
</ul>
</td>
</tr>
</tbody>
</table>

---

<div align="center">

**把上下文交给模型，把权限留给服务器与玩家。 · Give the model context. Keep authority with the server and its players.**

</div>
