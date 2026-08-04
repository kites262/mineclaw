# 运维手册

本文覆盖 Mineclaw 1.1.0 的安装、迁移、日常管理、诊断、构建和发布检查。

## 运行要求

- Paper 26.2 或 Folia 26.2
- Java 25
- 可访问的 OpenAI-compatible Chat Completions endpoint
- Tool 模式需要上游正确支持 streaming 和 tool calls

`26.2 + Java 25` 是当前精确目标，不是“最低版本”声明。独立 Spigot/Bukkit 和其他版本不在兼容承诺内。

## 全新安装

1. 停止服务端。
2. 把 `Mineclaw-1.1.0.jar` 放入 `plugins/`。
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

## 管理命令与权限

| 命令 | 默认权限节点 | 默认值 | 说明 |
| --- | --- | --- | --- |
| 公屏 `@ai ...` | `mineclaw.command.chat` | true | 使用公共 Agent |
| `/mineclaw clear` | `mineclaw.command.clear` | op | 清空公共历史并轮换 cache key |
| `/mineclaw compact` | `mineclaw.command.compact` | op | 强制压缩或排队 |
| 审批/选择内部命令 | `mineclaw.command.approve` | true | 点击组件，或无 UUID 的 `/mineclaw approve` |
| `/mineclaw reload` | `mineclaw.command.reload` | op | 原子重载控制面 |
| `/mineclaw tools [validate]` | `mineclaw.command.tools` | op | Tool 诊断，不执行副作用 |
| `/mineclaw functions [validate]` | `mineclaw.command.functions` | op | Function/Skill 引用诊断 |
| `/mineclaw model ...` | `mineclaw.command.model` | op | 查看或切换后续 Turn 模型 |
| 绕过聊天冷却 | `mineclaw.bypass.ratelimit` | false | 不绕过其他权限 |

`/mineclaw model` 显示当前选择；`list` 列目录；`default` 恢复 `providers.yml` 默认；完整 `provider/model` 只影响后续 Turn。

`/mineclaw approve` 不提供 UUID 时，会批准该玩家当前最新且仍有效的 confirm 请求。带 UUID 的 approve/reject 仍精确绑定一次性请求；select 必须提交明确 option，不会被无 UUID approve 自动选择。

## 修改配置

### 控制面

按以下顺序降低混合状态风险：

1. 先在副本中编辑 `config.yml`、`providers.yml`、`whitelist.yml` 和 `.env`。
2. 确认 YAML 没有重复 key、alias、旧字段或空环境引用。
3. 原子替换需要变更的文件。
4. 执行 `/mineclaw reload`。
5. 只有收到成功消息，候选快照才已发布；失败时查看安全诊断并修正，旧快照仍在服务。

### Tool、Function、Workspace 与消息

- `tools.yml` 和 `functions.yml` 为新 Turn 加载；先执行 validate。活动 Turn 保留开始时的目录快照。
- Workspace 文件按新 Turn/文件调用读取；修改 Skill 不需要控制面 reload。
- `message.yml` 用于后续消息与交互。保留所有必需 key 和占位符，修改后分别测试 Action Bar、最终回复、命令审批、Function confirm/select。
- `seed_defaults: true` 只补缺，不会把发行默认覆盖到已有文件；升级后新增默认案例需要管理员人工比较与引入。

## 上下文与手动压缩

默认模型配置：

```yaml
limits:
  context_window_tokens: 131072
  max_output_tokens: 16384
  compact_trigger_tokens: 102400
```

自动压缩达到界限后选择较旧的完整 Turn，用相同 Provider/模型快照发起无 Tool 摘要请求。当前 Turn、近期原文和 Tool 证据不会被塞进摘要后丢弃。

`/mineclaw compact`：

- 空闲时立即开始，即使模型没有配置 `compact_trigger_tokens`；
- 有活动 Turn 时排队并去重；
- Turn 成功、失败、超时或 Tool 上限后，先把完整结果写入 Session，再执行排队压缩；
- 手动压缩期间拒绝新 Turn，避免 Session generation 竞态；
- 失败或取消不发布摘要，原 Session 保持不变。

Provider 返回上下文溢出时，Mineclaw 最多做一次压缩恢复和一次重试；不会重复副作用，也不会无限重试。

## 常见诊断

### `control_plane_unavailable`

检查 `.env` 引用是否有值、Provider/model 引用是否完整、URL 是否不含 `/chat/completions`、请求扩展是否覆盖保留字段，以及 whitelist 正则是否合法。修正后 `/mineclaw reload`。

### Tool 无效或不可用

运行 `/mineclaw tools validate`。常见原因：Schema 不是 2、handler 未注册、function name 与 handler 不同、payload 多字段、被 `config.yml tools.disabled` 或总开关禁用。

### Function 无效

运行 `/mineclaw functions validate`。检查根版本、重复名称、参数 Schema、capability、`async function onCall(ctx, api)` 源码、源码大小，以及 Skill 是否引用了未知/disabled Function。

### JavaScript 超时

区分 `max_sync_segment_ms` 和 `max_workflow_ms`：前者限制不 yield 的同步计算，后者允许等待玩家交互和异步 Tool。默认工作流 300 秒已经覆盖冷启动与多人审批；不要用提高同步上限掩盖死循环或重计算。

### Provider 失败

连接、timeout、408、429 和 5xx 会按 transport 重试；普通 4xx 直接失败。确认上游支持 SSE streaming、tool calls、模型名和请求扩展。Provider 返回错误时，控制台直接显示上游响应原文；JSON 不再被拆字段或重写，SSE 错误也保留事件文本。响应最多保留 16 KiB，且可能包含上游回显的数据，应按敏感日志管理。

需要核对实际请求时，可临时设置 `logging.level: ALL` 并执行 `/mineclaw reload`。日志会保留完整 tools 与请求参数，但会把长消息截为前 100 个 Unicode 字符加 `...`。排障完成后恢复常规级别（默认 `INFO`）；即使没有凭据头，请求 Body 仍可能包含玩家对话和本服资料。

### Action Bar 没有颜色

确认 `message.yml` 使用合法 MiniMessage 颜色标签，模型输出标签正确闭合，并且没有使用模型不允许的交互标签。Action Bar 对段落采用当前流式帧替换并节流刷新，最终答复只在公屏发送一次。

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
build/plugins/Mineclaw-1.1.0.jar
```

构建使用 Java toolchain 25、Gradle Wrapper 9.5.0 和 dependency locking。JAR 会合并运行时依赖，排除签名文件、module descriptor 和所有 `.env`，并加入项目 LICENSE、NOTICE 与第三方许可证资源。

## v1.1.0 发布检查

发布候选至少完成：

1. 版本一致：Gradle、`paper-plugin.yml`、README、产物名均为 `1.1.0`。
2. `./gradlew --no-daemon clean test assemblePlugin` 全部通过。
3. 连续两次 clean build 的 JAR SHA-256 一致。
4. JAR 中不存在 `.env`、凭据、重复 entry 或签名残留，存在 LICENSE/NOTICE/第三方声明。
5. `paper-plugin.yml` 声明 Paper API 26.2、Folia supported 和完整权限。
6. Seed 首次生成与已有文件不覆盖两条路径都通过。
7. 四文件控制面成功/失败原子重载演练通过。
8. Tool/Function validate、内置药水、结构定位、拒绝/超时/取消、命令错误语义通过。
9. usage、自动压缩、手动即时/排队压缩、overflow 单次恢复通过。
10. README 和 docs 的本地链接有效，示例只使用 v1 Schema，源码与构建产物完成 secret scan。
11. 在 Paper 26.2 和 Folia 26.2 的 Java 25 运行环境做 smoke test。
12. 生成 release notes、记录 JAR SHA-256；仅在检查结论明确后创建 tag 和发布。

仓库操作本身不需要提交或推送即可完成构建与审计；部署、tag、GitHub Release 和生产迁移应作为单独的显式变更步骤执行。
