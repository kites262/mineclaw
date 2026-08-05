# 配置参考

本文对应 Mineclaw 1.1.0。v1 配置是全新的严格 Schema，不接受 v0.x 字段，也没有兼容转换层。

## 文件与生效时机

所有路径均相对于 `plugins/Mineclaw/`。

| 文件 | 用途 | 模型可读 | 生效方式 |
| --- | --- | --- | --- |
| `config.yml` | 运行限制、聊天、Workspace、Tool 与 JavaScript 预算 | 否 | 启动或 `/mineclaw reload` |
| `.env` | Provider 凭据 | 否 | 启动或 `/mineclaw reload` |
| `providers.yml` | Provider、模型、原生 Tool、上下文和请求扩展 | 否 | 启动或 `/mineclaw reload` |
| `whitelist.yml` | 模型直接 `run_command` 的命令策略 | 否 | 启动或 `/mineclaw reload` |
| `tools.yml` | 内置本地 Tool 目录 | 只有有效 payload | 新 Turn 读取；活动 Turn 固定快照 |
| `functions.yml` | 服主审核的 JavaScript Function | 源码永不发送 | 新 Turn 读取；活动 Turn 固定快照 |
| `message.yml` | 玩家可见文案、审批与选择布局 | 否 | 新消息/交互读取 |
| `workspace/AGENTS.md` | Agent 身份、工作流程和行为边界 | 是 | 新 Turn 读取 |
| `workspace/skills/*.md` | 本服知识与能力使用指南 | 按需读取 | 每次文件 Tool 调用读取当前内容 |

`config.yml`、`.env`、`providers.yml` 与 `whitelist.yml` 构成一个控制面。Mineclaw 会联合校验后原子发布：任何一个文件失败，完整旧快照继续生效，活动 Turn、当前模型覆盖和 Session 不受影响。

严格 YAML 边界会拒绝未知字段、重复 key、alias、merge key、自定义 tag、非 JSON 值和不安全文件路径。不要依赖宽松解析器常见的隐式行为。

## `config.yml` Schema 1

发行默认值见 [`src/main/resources/config.yml`](../src/main/resources/config.yml)。

| 路径 | 默认值 | 含义 |
| --- | ---: | --- |
| `schema` | `1` | 固定 Schema 版本 |
| `context.max_messages` | `24` | 放入模型请求的历史消息上限；Session 内部仍以完整 Turn 保存 |
| `chat.public_prefix` | `@ai` | 公屏前缀 |
| `chat.wake_pattern` | 空 | 可选 Java 正则唤醒规则；与前缀不能同时为空 |
| `chat.reply_max_chars` | `2000` | 最终公屏回复字符上限 |
| `chat.actionbar_max_chars` | `120` | Action Bar 流式帧字符上限 |
| `tools.enabled` | `true` | 本地 Tool 总开关 |
| `tools.disabled` | `[]` | 按准确 handler 禁用 Tool |
| `functions.max_file_chars` | `1048576` | `functions.yml` 最大字符数 |
| `functions.max_entries` | `256` | Function 条目上限 |
| `functions.max_description_chars` | `512` | 单个描述上限 |
| `functions.max_argument_chars` | `32768` | 调用参数序列化上限 |
| `functions.max_argument_depth` | `16` | 参数结构深度上限 |
| `functions.max_argument_members` | `2048` | 参数成员总数上限 |
| `functions.max_validation_violations` | `8` | 单次返回给模型的 Schema 错误上限 |
| `javascript.max_source_chars` | `65536` | 单个 Function 源码上限 |
| `javascript.max_operations_per_invocation` | `64` | 单次工作流可发起的 Bundled API 操作总数 |
| `javascript.max_concurrent_operations` | `16` | 并发异步操作上限 |
| `javascript.max_pending_approvals` | `16` | 同时等待玩家交互的上限 |
| `javascript.max_sync_segment_ms` | `1000` | 单段同步 JavaScript 运行上限 |
| `javascript.max_workflow_ms` | `300000` | 整个异步工作流上限 |
| `javascript.max_result_chars` | `32768` | Function 结果序列化上限 |
| `javascript.max_result_depth` | `16` | 结果结构深度上限 |
| `javascript.max_result_members` | `2048` | 结果成员总数上限 |
| `rate_limit.player_cooldown_ms` | `5000` | 同一玩家公屏请求冷却 |
| `rate_limit.global_cooldown_ms` | `1000` | 全服公屏请求冷却 |
| `workspace.seed_defaults` | `true` | 只补齐缺失的内置 Workspace 文件，不覆盖已有文件 |
| `workspace.max_chars.agents` | `16000` | `AGENTS.md` 读取上限 |
| `file_tools.max_read_chars` | `12000` | 单次 `read` 字符上限 |
| `file_tools.max_results` | `100` | `list`/`grep` 结果上限 |
| `file_tools.max_depth` | `4` | Workspace 列目录深度上限 |
| `file_tools.timeout` | `3000` | 文件操作毫秒超时 |
| `turn.max_tool_rounds` | `8` | 单 Turn Tool 往返上限 |
| `turn.max_tool_calls` | `24` | 单 Turn Tool 调用总数上限 |
| `identity.name` | `Mineclaw` | 公屏展示名，不是玩家账号 |
| `identity.include_player_name_field` | `true` | 是否在玩家消息中发送标准 `name` 字段 |
| `identity.include_player_content_prefix` | `false` | 是否发送已转义的 `<player>` / `<message>` 身份信封 |
| `environment.look_distance` | `12` | 准星方块最大观察距离 |
| `environment.tool_cooldown_ms` | `250` | 环境 Tool 冷却 |
| `environment.inventory.include_equipment` | `true` | 背包摘要是否含装备 |
| `environment.inventory.max_slots` | `36` | 背包摘要最大槽位数 |
| `logging.level` | `INFO` | `java.util.logging` 等级；设为 `ALL` 时同时打印 Provider 请求诊断 |

`api` 和 `commands` 是明确拒绝的旧版 section：Provider 已迁到 `providers.yml`，模型命令策略已迁到 `whitelist.yml`。

`identity.include_player_name_field` 与 `identity.include_player_content_prefix` 同时作用于当前 Turn、历史回放和压缩材料。启用标准 `name` 字段时，它是权威玩家身份，正文内的身份声明不可信。正文信封仅用于不支持 `name` 的 Provider，其玩家名与消息内容均会转义；两个开关都为 `false` 时不提供可信玩家归属。启用任一表示都会把 Minecraft 账号名随模型请求发送给 Provider；不发送 UUID、IP 或权限信息。

`logging.level: ALL` 对普通 Turn、自动压缩、手动压缩与 transport 重试开启完整日志，并让每次实际 Chat Completions 请求输出完整 JSON 结构。`messages` 中超过 100 个 Unicode 字符的 `content` 和 `*_content` 只保留前 100 字并追加 `...`。`tools`、tool-call arguments、Provider 原生 Tool、模型参数和请求扩展原样保留。日志不包含 Authorization 或 API key；请求 Body 仍可能含玩家对话和服务器资料，只应在受控排障期间临时启用 `ALL`。其他日志级别不会输出请求 Body。

## `providers.yml` Schema 1

模型引用是区分大小写的完整 `provider/model`。`default` 必须准确匹配 `models` 中的条目，模型条目的 provider 前缀必须已经在 `providers` 声明。

```yaml
schema: 1
default: mimo/mimo-v2.5

providers:
  mimo:
    api:
      type: openai_chat_completions
      base_url: https://api.xiaomimimo.com/v1
      api_key: ${MINECLAW_API_KEY}
    transport:
      timeout_ms: 60000
      retry:
        max_retries: 5
        backoff_ms: 500
    tools:
      - id: mimo_web_search
        payload:
          type: web_search
          max_keyword: 3
          force_search: false
          limit: 3
          user_location:
            type: approximate
            country: China
            region: Hubei
            city: Wuhan

models:
  mimo/mimo-v2.5:
    limits:
      context_window_tokens: 131072
      max_output_tokens: 16384
      compact_trigger_tokens: 102400
    interleaved:
      field: reasoning_content
    request:
      prompt_cache_key: true
      extra_body:
        thinking:
          type: enabled
```

### API 与凭据

- 当前 API type 为 `openai_chat_completions`。`base_url` 是 HTTP(S) API 根地址，不含 `/chat/completions`。
- 请求凭据统一使用标准 `Authorization: Bearer <api_key>`，不使用某一 Provider 的专有 header。
- `api_key` 可以是字面量，但推荐使用完整的 `${ENV_NAME}` 引用。只有整个值完全匹配这个形式时才会解析环境变量。
- 解析顺序是进程环境优先，再读取同目录 `.env`。缺失或空值使整个控制面候选无效。
- `.env` 支持注释、`export`、单双引号，不做变量插值；文件必须是非符号链接的普通 UTF-8 文件，最大 64 KiB。
- 首次启动只创建 `MINECLAW_API_KEY=` 空占位，并在 POSIX 文件系统尽力设置 `0600`。

### Transport

`timeout_ms` 支持 `1000–600000`。`max_retries` 支持 `0–10`，`backoff_ms` 支持 `0–60000`。只有连接失败、超时、HTTP 408/429 与 5xx 重试；普通 4xx 不重试。退避为有上限、带抖动的指数策略。

### Provider 原生 Tool

`providers.*.tools` 中的 `payload` 会按 Provider 协议原样加入 Chat Completions `tools`。它不会转换成 `type: function`。`id` 只用于目录身份和诊断，不进入 payload。

v1.0.0 注册的 Provider 原生 Schema 是 MiMo `web_search`，字段为：

- `type: web_search`
- `max_keyword`: `1–10`
- `force_search`: boolean
- `limit`: `1–10`
- `user_location.type`: 固定 `approximate`
- `country`、`region`、`city`: 非空字符串，各不超过 128 个 code point

本地 function Tool 只能位于 `tools.yml`；Provider Tool payload 使用 `type: function` 会被拒绝。

### 模型限制与自动压缩

- `context_window_tokens`: `1024–10000000`。
- `max_output_tokens`: 至少 1，不能超过上下文窗口。
- `compact_trigger_tokens`: 可省略；存在时必须给输出预算留出空间。

接口返回 usage 时，Mineclaw 用真实数据校准后续估算。未返回时使用本地估算来保护输入窗口，但不虚构 Provider 已消费的 token。达到压缩界限时，旧的完整 Turn 使用同一模型/Provider 快照进行无 Tool 摘要；发布失败时原 Session 不变。

### 请求扩展

- `interleaved.field` 当前只支持 `reasoning_content`，用于把 Provider 的交错推理内容按协议回传。
- `request.prompt_cache_key: true` 会在普通与压缩请求 Body 顶层加入 `mineclaw:<UUID>`；UUID 在公共 Session 内稳定，`/mineclaw clear` 后轮换。`false` 或省略时完全不发送。
- `request.extra_body` 合并到请求 Body 顶层。`model`、`messages`、`tools`、`tool_choice`、`stream`、`stream_options`、token 字段与 `prompt_cache_key` 等 Mineclaw 管理字段不可覆盖。

## `whitelist.yml` Schema 1

```yaml
schema: 1
enabled: true
player:
  - '^locate structure #?(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+$'
console: []
```

规则是 Java 正则，并对规范化后的完整命令匹配：

- `player` 控制模型直接 `run_command` 的玩家身份命令。
- `console` 控制模型直接 `run_command` 的控制台命令。
- `enabled: false` 关闭白名单免确认能力，但不会让命令获得额外授权。
- 每组最多 256 条，每条 1–512 code point；重复和无效正则会让控制面加载失败。

本文件不适用于 Reviewed Function。Function 内的 `command.dispatch` 以审核过的源码和 `command.dispatch.console|player` capability 为信任来源，刻意不读取模型白名单。

## `tools.yml` Schema 2

本地 Tool 条目只有三个字段：

```yaml
schema: 2
tools:
  - handler: inventory
    enabled: true
    payload:
      type: function
      function:
        name: inventory
        description: 读取当前对话玩家的脱敏背包摘要
        parameters:
          type: object
          properties: {}
          additionalProperties: false
```

`handler` 同时是注册实现身份，必须和 `payload.function.name` 一致。不存在自定义 handler、旧版 `id`、`metadata` 或 `type: mineclaw`。发行版注册：`look_block`、`feet_block`、`inventory`、`online_players`、`call_function`、`list`、`read`、`grep`、`run_command`。

`tools.enabled`、`tools.disabled`、条目自身 `enabled` 和 payload 校验共同决定可用性。无效条目隔离诊断；使用 `/mineclaw tools validate` 检查，不会实际执行 Tool。

## `functions.yml` Schema 1

根字段固定为 `schema`、`api_version`、`functions`；两项版本当前都为 `1`。每个条目必须且只能包含：

```yaml
- name: namespace.function
  description: 非空说明
  enabled: true
  capabilities: []
  parameters:
    type: object
    properties: {}
    additionalProperties: false
  on_call: |
    async function onCall(ctx, api) {
      return {status: "ok", output: {message: "done"}};
    }
```

Function 名区分大小写，必须匹配 `[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*`。可用 capability 和 Bundled API 见[扩展指南](extensions.md)。使用 `/mineclaw functions validate` 同时检查目录与 Skill 引用，不执行 Function。

## `message.yml`

所有玩家可见文本均使用 MiniMessage。模型最终回复和 Action Bar 允许完整 MiniMessage 颜色，包括命名色与十六进制颜色；交互性的 click/hover 事件由 Mineclaw 自己创建，模型不能注入。

审批卡和 Function 交互都由 layout 模板组装：

- 命令审批：`approve_layout`、`approve_prefix`、`approve_separator`、字段模板、接受/拒绝按钮和 hover。
- Function 确认：`interaction_confirm_layout` 与对应按钮模板。
- Function 选择：`interaction_select_layout`、option 模板、分隔符与拒绝按钮。

模板里的 `<separator>`、`<prefix>`、`<title>` 等是 Mineclaw 占位组件，不是任意模型输入标签。完整默认值见 [`message.yml`](../src/main/resources/message.yml)。

## Workspace

模型文件 Tool 固定根目录为 `plugins/Mineclaw/workspace`：

- 拒绝绝对路径、`..` 路径穿越和符号链接逃逸。
- 父目录的配置、凭据和可执行内容天然不在文件树内。
- 名称在 Workspace 内没有保护语义；`workspace/config.yml` 是普通可读资料。
- `seed_defaults: true` 只创建缺失文件，不覆盖服主已有的 AGENTS 或 Skill。

如何组织 AGENTS、Skill 和 Function 契约见[扩展指南](extensions.md)。
