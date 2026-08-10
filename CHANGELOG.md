# Changelog

Mineclaw 使用语义化版本。v1.0.0 是重新设计的第一个稳定大版本，与全部 v0.x 配置不兼容。

## 1.3.0 — 2026-08-10

### Provider Tool

- `providers.yml` 中的 Provider Tool payload 不再受 MiMo `web_search` 硬编码内部 Schema 限制；其外层仍须为 JSON object，内部字段不校验、不改写并原样发送给对应上游。

### 公共会话身份

- 明确告知模型当前与历史每条玩家消息各自的权威身份来源，避免将标准 `name` 字段误解为仅对当前消息有效。
- 四种玩家身份开关组合分别声明 `name` 字段、正文信封或无可信归属的语义。

### 连续监听

- 新增 `/mineclaw listen [on|off]` 和 `mineclaw.command.listen` 权限，可在运行期开启或查看全服连续监听。
- 开启后，未带唤醒前缀的普通公屏消息也会进入 Mineclaw；成功接受的隐式唤醒消息会在公屏补上 AI 前缀，原有聊天权限、冷却与全服单 Turn 约束仍然生效。
- 监听开关只存在于当前插件进程，插件禁用或服务器重启后回到关闭状态。

### Action Bar

- 第一轮模型响应继续按 Delta 节流渲染并在完成后保持；后续工具调用中间响应只在完整接收后原子替换当前帧。
- 模型返回 Tool Call 时显示单帧 `◆ <tool>, <tool>... ◆` 安全名称摘要；两侧使用不参与 Emoji 呈现的 Unicode 几何符号，纯 `tool_calls` 响应不再只保留 `Thinking...`。
- 发生 Tool Call 后的最终公屏回复不再覆盖 Action Bar，公屏发送后让最后一个中间帧自然淡出；第一轮无 Tool 回复仍保留原有流式展示。

### Session 完整性

- 成功 Turn 不再压扁为 user 与最终 assistant 文本；assistant Tool Call、Tool Result、Provider 回放字段和未截断最终回复作为一个完整 Turn 原子写入无损 Session 档案。
- `max_messages` 与上下文压缩只调整送给模型的上下文投影，不再删除当前插件进程中的原始完整 Turn；进程存续期间只有显式 clear 会清空档案，插件重启仍会重建内存 Session。
- 普通 Turn 的每次模型响应请求最多尝试三次；仍失败时丢弃整个未完成 Turn 并向玩家报错，不再把合成失败记录写入 Session。

### 文档与集成

- 更新 KitesPlaces 示例 Skill，区分不覆盖的 `warp create` 与明确覆盖的 `warp set`，并同步新的 Unicode 传送点名称规则。

## 1.2.0 — 2026-08-05

### 高级环境感知

- 新增 `player_snapshot`，一次读取当前对话玩家的位置、生存与移动状态、局部世界环境和有效状态效果。
- 新增 `item_inspect`，支持紧凑背包摘要与指定存储槽、主副手和装备位详情，并以槽位数和输出字符数双重限制约束结果。
- 新增 `block_inspect`，统一检查准星目标或脚下方块，返回结构化 BlockData、光照、生物群系和有界方块实体类别。
- 三个新 Tool 均绑定本轮发起玩家，沿用环境 Tool 冷却、Folia 调度、取消、异常归一化和 Turn 快照语义。

### 公共会话身份

- 启用任一玩家身份表示时，当前 Turn、成功或失败历史以及自动、手动压缩材料保留每条玩家消息的 Minecraft 账号归属。
- 默认使用 Chat Completions 的标准 `name` 字段；可选启用已转义的 `<player>` / `<message>` 正文信封作为 Provider 兼容表示。
- 标准 `name` 字段开启时是唯一权威身份来源；玩家正文中的身份标记或声明始终视为不可信数据。

### 稳定性

- 修复 `item_inspect` 读取普通耐久物品时错误调用自定义最大耐久 API，导致背包摘要返回 `IllegalStateException` 的问题。
- 普通物品使用材质默认最大耐久；仅在物品显式覆盖最大耐久时读取对应元数据。

### 默认运行预算

- 默认历史消息上限从 `24` 调整为 `240`，单 Turn Tool 往返与调用上限从 `8`/`24` 调整为 `80`/`240`。
- 同一玩家、同一环境 Tool 的默认冷却从 `250 ms` 调整为 `10 ms`。

### Breaking changes

- 删除 `look_block`；改用 `block_inspect` 的 `look` 模式。
- 删除 `feet_block`；改用 `block_inspect` 的 `feet` 模式。
- 删除 `inventory`；改用 `item_inspect` 的 `inventory` 模式。
- 不提供旧 handler 的别名或兼容转发。自定义 `tools.yml`、Function capability 和 Workspace Skill 必须更新为新名称。
- 环境配置将 `environment.inventory` 替换为 `environment.item_inspect`，并新增 `max_output_chars` 结果预算。

## 1.1.0 — 2026-08-04

### 请求调试

- `logging.level: ALL` 同时开启完整日志与请求诊断，不再使用独立的顶层 `debug` 开关。
- 启用后，每次实际 Chat Completions 请求（包括重试与上下文压缩）在控制台打印请求 Body。
- `messages` 中超过 100 个 Unicode 字符的 `content`/`*_content` 只保留前 100 字并追加 `...`；实际 Provider 请求不被修改。
- `tools`、tool-call arguments、模型参数、Provider 原生 Tool 与请求扩展在调试日志中原样保留；Authorization 与 API key 不进入日志。

### Provider 错误响应

- 错误处理不再解析或重写 JSON 字段，控制台直接显示受 16 KiB 上限保护的上游响应原文。
- SSE 错误响应保留事件文本，不再作为无法解析的 JSON 被省略。

### 命令与审批

- `run_command.player` 统一为必填字符串：空字符串表示控制台，非空字符串表示在线玩家的准确账号名或 UUID；不再接受 JSON `null`。
- `/mineclaw approve` 不提供 UUID 时，批准该玩家当前最新且仍有效的 confirm 请求；select 仍要求明确 option。

## 1.0.1 — 2026-08-04

### 玩家审批

- `/mineclaw approve` 不提供 UUID 时，批准该玩家当前最新且仍有效的 confirm 请求。
- `/mineclaw approve <UUID>`、`/mineclaw reject <UUID>` 与点击组件继续按一次性 UUID 精确匹配。
- select 请求不会被无 UUID 的 approve 代替选择，仍需提交明确 option。

## 1.0.0 — 2026-08-04

### Agent runtime

- 将模型工作根固定为 `plugins/Mineclaw/workspace`，配置、凭据和可执行内容与模型文件树物理分离。
- 发行结构化 `workspace/AGENTS.md`，统一 Agent 背景、工作节奏、结果证据、安全边界和 MiniMessage 颜色规则。
- 提供实时方块、位置、背包、在线账号与 Workspace 检索 Tool；Action Bar 支持彩色流式渲染。

### Tool 与 Provider

- `tools.yml` 升级为不兼容 Schema 2；`handler` 是唯一身份，不再存在 id、metadata 或自定义 Tool type。
- 本地 Tool 按 `type: function` 投影到 Chat Completions；Provider 原生 Tool 保留原始 payload。
- 新增 Provider handler 能力，并内置 MiMo `web_search` 配置示例。
- Provider 请求使用标准 Bearer 凭据头；支持重试、SSE、usage、`reasoning_content` interleaved replay 与安全错误解析。
- 新增模型级 `request.prompt_cache_key`，按公共 Session 生成稳定 key，并在 clear 时轮换。

### JavaScript Function

- 新增严格 `functions.yml`、固定 `call_function` 网关、JSON Schema 参数校验和 Skill 引用验证。
- 新增独立 Graal JavaScript 沙箱、同步/总时限、操作/并发/审批/结果预算与取消传播。
- 新增 confirm/select 玩家交互、显式 capability、本地 Tool 调用和可信玩家/控制台命令分发。
- Reviewed Function 命令不再与模型白名单耦合；完整保留错误码、审批、操作、分发、执行与反馈上下文。
- Seed 内置生产提炼的 `player.effect.give` 完整示例。

### 上下文

- 默认上下文 128K、最大输出 16K、自动压缩界限 100K。
- 优先使用 Provider usage；缺失时只做本地窗口估算，不制造本地消费预算。
- 新增原子自动压缩、Provider overflow 单次恢复和 `/mineclaw compact` 即时/排队压缩。
- 成功、失败、超时和 Tool 上限 Turn 均保留必要上下文与调用证据。

### 配置与交互

- 配置重整为 `config.yml`、`.env`、`providers.yml`、`whitelist.yml` 四文件原子控制面。
- 所有严格 YAML Schema 拒绝未知字段、重复 key、alias、merge 与自定义 tag。
- 命令审批与 JavaScript confirm/select 布局、字段、按钮、hover 和分隔符可由 `message.yml` 配置。
- 模型回复与 Action Bar 支持完整 MiniMessage 命名色和十六进制颜色。

### Breaking changes

- 不读取、不迁移、不兼容 v0.x `config.yml`、旧 Tool Schema、旧命令字段或旧 Workspace 布局。
- 删除独立 `guide.md` 与 `command-safety.md` Seed；内容已经重整并吸收到 `workspace/AGENTS.md`。
- 自定义 Tool 目录不再存在；自定义业务改用 Skill + JavaScript Function，新的 Java Tool 必须由代码注册。

升级步骤见 [`docs/operations.md`](docs/operations.md#从-v0x-迁移)。
