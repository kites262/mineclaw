# Changelog

Mineclaw 使用语义化版本。v1.0.0 是重新设计的第一个稳定大版本，与全部 v0.x 配置不兼容。

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
