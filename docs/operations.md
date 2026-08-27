# 运维手册

本文覆盖 Mineclaw 1.4.0 的安装、迁移、日常管理、诊断、构建和发布检查。

## 运行要求

- Paper 26.2 或 Folia 26.2
- Java 25
- 可访问的 OpenAI-compatible Chat Completions 或 Responses API 根地址
- Tool 模式需要上游正确支持所选协议的 streaming、typed event/chunk 和 tool calls

`26.2 + Java 25` 是当前精确目标，不是“最低版本”声明。独立 Spigot/Bukkit 和其他版本不在兼容承诺内。

## 全新安装

1. 停止服务端。
2. 把 `Mineclaw-1.4.0.jar` 放入 `plugins/`。
3. 启动一次，使插件生成 `plugins/Mineclaw/`，再停止服务端。
4. 在 `.env` 写入 `MINECLAW_API_KEY`，按需编辑 `providers.yml`。
5. 审核默认 `whitelist.yml`、`functions.yml` 和 Workspace。
6. 启动服务端，观察控制台是否生成有效控制面快照。
7. 依次运行 `/mineclaw tools validate`、`/mineclaw functions validate`、`/mineclaw model list`。
8. 用普通玩家在公屏测试 `@ai`，再测试内置读取、结构定位和药水审批流程。

不要在服务端运行时直接覆盖 JAR。常规升级应停服、备份、替换再启动。

## 从 v0.x 迁移

v1.0.0 是破坏性版本：不保留旧配置读取、字段别名、自动迁移或兼容行为。推荐“新目录生成 + 人工迁移意图”，而不是在旧文件上修补。

1. 停止服务端并备份完整 `plugins/Mineclaw/` 和旧 JAR。
2. 把旧目录重命名为一个明确备份路径，例如 `Mineclaw-v0-backup-YYYYMMDD`。
3. 安装 v1 JAR 并启动一次，让它生成全新的 Schema 1/2 文件。
4. 停服后逐项迁移：

   | v0.x 内容 | v1 目标 |
   | --- | --- |
   | API URL、key、模型 | `providers.yml` + `.env` |
   | command enable/玩家/控制台白名单 | `whitelist.yml` Schema 1 |
   | guide、安全规则、Agent 身份 | 重新审阅并合入 `workspace/AGENTS.md` |
   | server Skill | `workspace/skills/*.md`，更新 Function/Tool 名 |
   | Tool metadata/id/type | 不迁移；按 `tools.yml` Schema 2 的固定 handler |
   | 自定义 Tool | 重写为 Skill + `functions.yml` Function，或 Java 代码贡献 |
   | 玩家消息 | 按全新 `message.yml` key 人工重做 |

5. 不要复制旧 `config.yml`、`tools.yml` 或整个旧数据目录覆盖新文件。
6. 运行配置/Tool/Function 校验，并逐个业务场景演练审批、失败和结果语义。
7. 保留备份到完成一个可接受的回滚观察期。

回滚到 v0.x 时必须同时恢复旧 JAR 和完整旧数据目录。不要让两个大版本共享同一套可写配置。

## 从 v1.1.x 升级

v1.2.0 替换了环境感知 Tool 名称和对应配置，不提供旧名称兼容层：

| v1.1.x | v1.2.0 |
| --- | --- |
| `look_block` | `block_inspect`，`mode: look` |
| `feet_block` | `block_inspect`，`mode: feet` |
| `inventory` | `item_inspect`，`mode: inventory` |
| `environment.inventory.max_slots` | `environment.item_inspect.max_slots` |
| `environment.inventory.include_equipment` | 删除；摘要始终包含存储槽、装备位、主手和副手 |
| 无 | `environment.item_inspect.max_output_chars` |
| 无 | `identity.include_player_name_field`，默认 `true` |
| 无 | `identity.include_player_content_prefix`，默认 `false` |

停服并备份后，替换 JAR，让默认资源生成到临时目录以核对新结构。更新现有 `config.yml`、`tools.yml`、Function 的 `native_tool.call.<handler>` capability 以及 Workspace Skill 中的调用名称，再启动并运行 Tool/Function 校验。建议在现有配置中显式写入两个玩家身份开关；默认标准 `name` 字段会把当前与历史玩家的 Minecraft 账号名发送给 Provider。v1.2.0 同时将默认 `context.max_messages` 调整为 `240`、Turn Tool 往返/调用预算调整为 `80`/`240`、环境 Tool 冷却调整为 `10 ms`；现有配置不会被 Seed 自动覆盖，需由管理员决定是否同步。严格 Schema 会拒绝残留的 `environment.inventory`；旧 handler 不可注册或经 Function 间接调用。

## 从 v1.2.x 升级

v1.3.0 不增加必填的控制面字段，现有 v1.2.x `config.yml`、`.env`、`providers.yml` 和 `whitelist.yml` 可继续加载。常规停服、备份并替换 JAR 后，重点核对以下行为：

- Provider Tool 的 `payload` 不再限制为 MiMo `web_search` Schema，而是作为 JSON object 原样透传。现有配置继续有效，但上游字段和副作用需由管理员自行审核。
- 普通 Turn 的每次模型响应请求最多使用两次 transport retry（三次总尝试）；上下文压缩请求仍使用配置的 `max_retries`。
- 只有产生最终回复的已完成 Turn 会无损写入 Session；未完成 Turn 不再生成合成失败历史。`context.max_messages` 与压缩只修改后续模型请求使用的投影，不删除内存中的已完成 Turn 档案。
- 新增进程内连续监听和 `mineclaw.command.listen`；它不是持久配置，插件禁用或服务器重启后会关闭。
- 现有 `message.yml` 不会自动覆盖。手工合入 `listen_enabled`、`listen_disabled`、`listen_status_on`、`listen_status_off`、`actionbar_tools_called` 和新 `usage`；未合入的新 key 会回退到 JAR 内默认文案并记录一次警告。

升级后运行 Tool/Function validate，再演练连续监听的权限、冷却、busy 和重启重置，以及含 Tool Call 的 Action Bar 和 Session 完整性。

## 从 v1.3.x 升级

v1.4.0 新增可选的 `openai_responses` Provider 协议；现有 `openai_chat_completions` Provider、配置字段和 Session 行为继续有效。常规停服、备份并替换 JAR 后，重点核对以下行为：

- 不需要修改现有 Chat Provider。若启用 Responses，新增独立 Provider/model，`base_url` 只写 API 根地址，由 Mineclaw 追加 `/responses`；不要把 `/responses` 或 `/chat/completions` 写入 URL。
- Responses 使用 `input` items、typed SSE 和扁平本地 Function Schema；Provider 原生 Tool payload 不会跨协议转换，必须按上游 Responses 形状单独审核。
- Responses 请求显式使用 `store: false`，并在本地保存、回放 reasoning、`function_call` 与 `function_call_output` items；不依赖 `previous_response_id` 或 Provider 侧会话。`interleaved` 只适用于 Chat。
- 官方 Responses input message 没有 `name` 字段。启用任一玩家身份开关时，Responses 自动使用已转义的 `<player>` / `<message>` 正文信封；Chat 仍使用 `name` 字段。

升级后先运行控制面、Tool/Function validate，再按“配置并验证两种 Provider 协议”矩阵分别演练纯文本、Tool 往返、多轮回放、reasoning、压缩、失败和重试；确认旧 Chat 模型仍为默认模型后再切换生产流量。

## 管理命令与权限

| 命令 | 默认权限节点 | 默认值 | 说明 |
| --- | --- | --- | --- |
| 公屏 `@ai ...` | `mineclaw.command.chat` | true | 使用公共 Agent |
| `/mineclaw listen [on\|off]` | `mineclaw.command.listen` | op | 切换服务器级连续监听；成功接受的无前缀公屏消息补上 AI 前缀，服务器重启后重置 |
| `/mineclaw clear` | `mineclaw.command.clear` | op | 清空公共历史并轮换 cache key |
| `/mineclaw compact` | `mineclaw.command.compact` | op | 强制压缩或排队 |
| 审批/选择内部命令 | `mineclaw.command.approve` | true | 点击组件，或无 UUID 的 `/mineclaw approve` |
| `/mineclaw reload` | `mineclaw.command.reload` | op | 原子重载控制面 |
| `/mineclaw tools [validate]` | `mineclaw.command.tools` | op | Tool 诊断，不执行副作用 |
| `/mineclaw functions [validate]` | `mineclaw.command.functions` | op | Function/Skill 引用诊断 |
| `/mineclaw model ...` | `mineclaw.command.model` | op | 查看或切换后续 Turn 模型 |
| 绕过聊天冷却 | `mineclaw.bypass.ratelimit` | false | 不绕过其他权限 |

`/mineclaw model` 显示当前选择；`list` 列目录；`default` 恢复 `providers.yml` 默认；完整 `provider/model` 只影响后续 Turn。

`/mineclaw listen` 查看状态，`on` / `off` 切换状态。开启后，普通公屏消息与显式前缀消息使用相同的 `mineclaw.command.chat`、冷却和全服单 Turn 约束；只有成功接受的无前缀消息会在公屏补上当前 `chat.public_prefix`。该状态不受 `/mineclaw reload` 影响，插件禁用或服务器重启时重置。

`/mineclaw approve` 不提供 UUID 时，会批准该玩家当前最新且仍有效的 confirm 请求。带 UUID 的 approve/reject 仍精确绑定一次性请求；select 必须提交明确 option，不会被无 UUID approve 自动选择。

## 修改配置

### 控制面

按以下顺序降低混合状态风险：

1. 先在副本中编辑 `config.yml`、`providers.yml`、`whitelist.yml` 和 `.env`。
2. 确认 YAML 没有重复 key、alias、旧字段或空环境引用。
3. 原子替换需要变更的文件。
4. 执行 `/mineclaw reload`。
5. 只有收到成功消息，候选快照才已发布；失败时查看安全诊断并修正，旧快照仍在服务。

### 配置并验证两种 Provider 协议

`openai_chat_completions` 与 `openai_responses` 可以作为不同 Provider 同时存在。切换协议时不要直接改写唯一的生产 Provider；先复制为独立 id，让两个模型条目引用各自 Provider，再逐一验收。它们可以复用同一个 `.env` 引用，但上游必须真实支持对应 endpoint 和线格式。

1. 为 Chat 与 Responses 分别声明 Provider。两者的 `base_url` 都只写 API 根，不能带 `/chat/completions` 或 `/responses`；Mineclaw 会按 type 追加 endpoint。
2. Provider 原生 Tool 的 `payload` 分别使用上游对应协议的 Schema。Mineclaw 只会自动转换 `tools.yml` 中的本地 Function Tool：Chat 使用嵌套 `function`，Responses 使用扁平 `name`、`description` 与 `parameters`。
3. Responses 模型删除 `interleaved`；它只属于 Chat 的 `reasoning_content` 回放。Responses 不发送官方 input message 未定义的 `name`；启用 `identity.include_player_name_field` 时会自动改用已转义的正文身份信封，无需为协议切换改动全局身份配置。
4. 原子替换 `providers.yml`（以及确有需要的 `config.yml`）并运行 `/mineclaw reload`；失败时确认旧快照仍可用。运行 `/mineclaw model list`，核对两个完整模型名。
5. 切到 Chat 模型，依次验证纯文本流式回复、本地只读 Tool Call、Tool Result 后续请求、多轮上下文和一次无 Tool 压缩。确认请求走 `/chat/completions`、使用 `messages`，Function Schema 为嵌套形状。
6. 切到 Responses 模型，重复同一组场景，并额外验证 `response.output_text.delta`、terminal completed/incomplete response、usage、带 opaque/encrypted content 的 reasoning item、`function_call` arguments、匹配 `call_id` 的 `function_call_output` 与后续完整本地回放。请求必须走 `/responses`、使用 `input`、Function Schema 为扁平形状，并显式包含 `store: false` 与 `include: [reasoning.encrypted_content]`。
7. 分别为两种协议验证一个与协议匹配的只读 Provider 原生 Tool；不要用本地 Tool 成功来代替 Provider payload 验收。
8. 检查 INFO 级日志中的成功、重试和失败边界，确认没有未知 typed event、孤立 function call、重复副作用或凭据。测试完成后先 `/mineclaw model default`，再决定是否移除临时 Provider。

只有需要核对线格式时才短时使用 `logging.level: ALL`；它会记录两种协议的请求 Body，其中可能包含玩家对话、reasoning、Tool 参数/结果和本服资料。完成核对后立即恢复 INFO。`store: false` 只是不让 Mineclaw 依赖 Provider 侧响应存储，不能替代上游数据留存审查。

### Tool、Function、Workspace 与消息

- `tools.yml` 和 `functions.yml` 为新 Turn 加载；先执行 validate。活动 Turn 保留开始时的目录快照。
- Workspace 文件按新 Turn/文件调用读取；修改 Skill 不需要控制面 reload。
- `message.yml` 用于后续消息与交互。保留所有现有 key 和占位符，新版添加 key 时按当前主题手工合入；缺失 key 会警告并使用 JAR 内默认值。修改后分别测试连续监听文案、Action Bar、最终回复、命令审批、Function confirm/select。
- `seed_defaults: true` 只补缺，不会把发行默认覆盖到已有文件；升级后新增默认案例需要管理员人工比较与引入。

## 上下文与手动压缩

默认模型配置：

```yaml
limits:
  context_window_tokens: 131072
  max_output_tokens: 16384
  compact_trigger_tokens: 102400
```

成功 Turn 以完整 user、assistant Tool Call、Tool Result、Provider 回放字段和未截断最终回复写入内存档案。`context.max_messages` 只限制模型上下文投影；自动压缩达到界限后选择较旧的完整 Turn，用相同 Provider/模型快照发起无 Tool 摘要请求，只替换后续请求使用的投影，不删除无损档案。

`/mineclaw compact`：

- 空闲时立即开始，即使模型没有配置 `compact_trigger_tokens`；
- 有活动 Turn 时排队并去重；
- 活动 Turn 结束后再执行排队压缩；成功 Turn 先写入 Session，失败、超时或 Tool 上限 Turn 不写入；
- 手动压缩期间拒绝新 Turn，避免 Session generation 竞态；
- 失败或取消不发布摘要，原 Session 保持不变。

Provider 返回上下文溢出时，Mineclaw 最多做一次压缩恢复和一次重试；不会重复副作用，也不会无限重试。

普通 Turn 的每次模型响应请求最多三次总尝试。仍失败时整个未完成 Turn 不写入 Session；如果此前已经分发有副作用的 Tool，依据命令/调用审计核对实际结果，不要因历史中没有该 Turn 就自动重试。

## 常见诊断

### `control_plane_unavailable`

检查 `.env` 引用是否有值、Provider/model 引用是否完整、API type 是否为 `openai_chat_completions` 或 `openai_responses`、`base_url` 是否不含 `/chat/completions` 与 `/responses`、Responses 模型是否误配 `interleaved`、请求扩展是否覆盖保留字段，以及 whitelist 正则是否合法。修正后 `/mineclaw reload`。

### Tool 无效或不可用

运行 `/mineclaw tools validate`。常见原因：Schema 不是 2、handler 未注册、function name 与 handler 不同、payload 多字段、被 `config.yml tools.disabled` 或总开关禁用。

### Function 无效

运行 `/mineclaw functions validate`。检查根版本、重复名称、参数 Schema、capability、`async function onCall(ctx, api)` 源码、源码大小，以及 Skill 是否引用了未知/disabled Function。

### JavaScript 超时

区分 `max_sync_segment_ms` 和 `max_workflow_ms`：前者限制不 yield 的同步计算，后者允许等待玩家交互和异步 Tool。默认工作流 300 秒已经覆盖冷启动与多人审批；不要用提高同步上限掩盖死循环或重计算。

### Provider 失败

响应解析损坏、连接、timeout、408、429 和 5xx 会按 transport 重试；普通 4xx 直接失败。普通 Turn 的单次模型响应最多三次总尝试，压缩请求则使用完整 `max_retries`。确认 API type、派生 endpoint、模型名、请求扩展、Provider payload 和上游实际能力一致：Chat Completions 应返回协议对应的 SSE chunk，Responses 应返回 typed SSE event，不能把两者混用。Tool 场景还要检查 Responses 的 function call、arguments 和 output 是否使用同一 `call_id`。Provider 返回错误时，控制台直接显示上游响应原文；JSON 不再被拆字段或重写，SSE 错误也保留事件文本。响应最多保留 16 KiB，且可能包含上游回显的数据，应按敏感日志管理。

需要核对实际请求时，可临时设置 `logging.level: ALL` 并执行 `/mineclaw reload`。日志会保留完整 tools 与请求参数，但会把长消息截为前 100 个 Unicode 字符加 `...`。排障完成后恢复常规级别（默认 `INFO`）；即使没有凭据头，请求 Body 仍可能包含玩家对话和本服资料。

### Action Bar 没有颜色

确认 `message.yml` 使用合法 MiniMessage 颜色标签，模型输出标签正确闭合，并且没有使用模型不允许的交互标签。Action Bar 对第一轮模型响应按 Delta 节流刷新；后续中间响应只在完整接收且继续 Tool Call 时一次性替换当前帧。Tool Call 使用 `actionbar_tools_called` 在单帧中按返回顺序显示 `◆ <tool>, <tool>... ◆`，两侧使用 Unicode 几何符号；不向玩家展示 Tool arguments 或结果正文。发生 Tool Call 后的最终答复只在公屏发送，Action Bar 保留最后一个中间帧自然淡出；第一轮无 Tool 回复仍会流式显示。

### 命令显示已分发但没有效果

先看 `dispatch_status`、`execution_result` 和 `feedback`。玩家身份命令通常无法同步获得插件反馈，命令权限、参数和目标插件结果需在服务端侧确认。不要自动重试可能已有副作用的命令。

## 日志、备份和监控

- 备份 JAR、四个控制面文件、Tool/Function/Message 以及整个 Workspace；按敏感数据策略单独处理 `.env`。
- 监控 Provider timeout/429/5xx、Turn 失败、压缩失败、Function terminal error、JavaScript timeout、审批超时和命令审计事件。
- Function 命令审计包含 invocation、Function 名、源码 hash、Turn 玩家、executor、intent、结果以及 `trust_source=reviewed_function`。
- Session 只在内存中，不要把它当持久记录；需要合规审计时使用服务端日志体系并控制访问权限。

## 从源码构建

```bash
./gradlew --no-daemon clean test assemblePlugin
```

产物：

```text
build/plugins/Mineclaw-1.4.0.jar
```

构建使用 Java toolchain 25、Gradle Wrapper 9.5.0 和 dependency locking。JAR 会合并运行时依赖，排除签名文件、module descriptor 和所有 `.env`，并加入项目 LICENSE、NOTICE 与第三方许可证资源。

## v1.4.0 发布检查

发布候选至少完成：

1. 版本一致：Gradle、`paper-plugin.yml`、README、产物名均为 `1.4.0`。
2. `./gradlew --no-daemon clean test assemblePlugin` 全部通过。
3. 连续两次 clean build 的 JAR SHA-256 一致。
4. JAR 中不存在 `.env`、凭据、重复 entry 或签名残留，存在 LICENSE/NOTICE/第三方声明。
5. `paper-plugin.yml` 声明 Paper API 26.2、Folia supported 和包含 `mineclaw.command.listen` 的完整权限。
6. Seed 首次生成与已有文件不覆盖两条路径都通过。
7. 四文件控制面成功/失败原子重载演练通过。
8. Tool/Function validate、新环境 Tool、内置药水、结构定位、拒绝/超时/取消、命令错误语义通过。
9. usage、连续监听、多玩家身份回放、完整 Turn 档案、自动压缩、手动即时/排队压缩、失败 Turn 不发布和 overflow 单次恢复通过。
10. README 和 docs 的本地链接有效，示例只使用 v1 Schema，源码与构建产物完成 secret scan。
11. 在 Paper 26.2 和 Folia 26.2 的 Java 25 运行环境做 smoke test。
12. 生成 release notes、记录 JAR SHA-256；仅在检查结论明确后创建 tag 和发布。

环境 Tool smoke test 必须覆盖 `player_snapshot`、`item_inspect` 的摘要/指定槽位/空槽/截断，以及 `block_inspect` 的 `look`/`feet`/无目标路径；同时确认旧 handler 在目录和 `native_tool.call` 中均被拒绝。

v1.3.0 专项 smoke test 还应覆盖 `listen` 状态/on/off、非 OP 权限、隐式消息前缀、冷却/busy 与重启重置；Action Bar 首轮流式、后续原子替换、Tool 安全名称和最终公屏；完整 Tool transcript 档案、投影压缩不删档案，以及三次尝试后失败 Turn 不发布。

v1.4.0 专项 smoke test 还应执行上文“双协议配置与验收”的完整矩阵：Chat Completions 与 Responses 各自覆盖纯文本、reasoning、local Tool、Provider Tool、多轮与压缩；确认 endpoint、请求容器、Function Schema、SSE 事件和回放 item 均未跨协议串线，Responses 始终为 `store: false`。

仓库操作本身不需要提交或推送即可完成构建与审计；部署、tag、GitHub Release 和生产迁移应作为单独的显式变更步骤执行。
