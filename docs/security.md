# 安全模型

Mineclaw 把权限放在运行时边界，而不是寄希望于 Prompt。本文说明 v1.0.0 的信任来源、隔离范围和不能保证的事项。

## 信任矩阵

| 来源 | 可以影响模型 | 可以直接产生副作用 | 实际约束 |
| --- | --- | --- | --- |
| 玩家公屏输入 | 是 | 否 | 单 Turn、权限、速率限制、上下文预算 |
| Workspace AGENTS/Skill | 是 | 否 | 只读文件根、大小/深度/超时；文档不是授权 |
| 本地只读 Tool | 是 | 否 | 固定 Java handler、Folia 调度边界、结果脱敏 |
| Provider 原生 Tool | 是 | 由 Provider 定义 | 严格注册的 Provider payload Schema |
| 模型 `run_command` | 是 | 可能 | 本轮白名单、执行身份、目标玩家审批、结果语义 |
| Reviewed Function | 模型只见调用契约 | 可能 | 参数 Schema、源码审核、capability、独立 JS 沙箱 |
| 控制面配置与 `.env` | 否 | 决定系统边界 | 固定父目录、严格 YAML、原子快照、不可由文件 Tool 访问 |

## 两条命令路径

### 模型直接 `run_command`

模型必须先从 Skill 获得命令形状，再提供 `command`、`intent` 和明确的 `player`；`player: null` 表示控制台。

- 当前对话玩家自己执行且完整命中 `whitelist.yml.player` 时可直接分发。
- 控制台命令必须完整命中 `whitelist.yml.console`。
- 指定其他玩家时必须用准确在线账号，并由该目标玩家本人确认。
- 未获授权、拒绝、超时、离线或请求失效时不会分发。

白名单是模型入口的策略，不是对所有命令执行的全局拦截器。

### Reviewed Function `command.dispatch`

这是服主 JavaScript 在显式 `command.dispatch.console|player` capability 内使用的基础操作。它不读取模型白名单，也不会弹出模型命令审批；如果业务需要同意，Function 必须先显式调用 `approval.request` 并检查结果。

这样做避免把可信程序逻辑错误耦合到模型白名单，同时把信任责任放到可审查的 Function 源码、参数 Schema、source hash 和 capability 上。模型无法直接调用 `command.dispatch`，也无法改变 Function 未暴露的代码路径。

两条路径不可互相替代：Skill 必须明确应该走哪一条，Agent 不能借 Function 绕过直接命令规则，也不能借 `run_command` 改写 Function 的固定流程。

## 命令结果证据

Mineclaw 区分“请求被接受”“命令已分发”和“游戏效果已完成”。

| 证据 | 可以说 | 不可以说 |
| --- | --- | --- |
| `approval_status: approved` | 玩家批准了请求 | 命令已经执行 |
| `dispatch_status: accepted` | 命令已交给服务端分发 | 目标插件接受了业务参数 |
| `execution_result: unknown` | 执行效果未知 | 已传送、已创建、已给予效果 |
| 明确控制台 `feedback` | 原样概括该反馈证明的内容 | 推断反馈没有覆盖的后续状态 |
| error/timeout/cancelled | 本流程在该点失败或终止 | 自动假设后续步骤完成 |

Function 结果会原样保留 `output.error_code`、`approval_status`、`operation_status`、`dispatch_status`、`execution_result`、`feedback` 和必要实体上下文。失败/超时 Turn 连同 Tool 证据会加入下一轮上下文，防止模型只看到泛化错误后继续误判。

玩家身份执行通常无法同步捕获游戏内反馈。对这类结果保守表述是协议要求，不是文案偏好。

## Workspace 隔离

文件 Tool 和 JavaScript 需要文件能力时都以 `plugins/Mineclaw/workspace` 为根：

- 路径先规范化，再拒绝绝对路径与越界；
- 中间与目标符号链接不能逃逸；
- 父目录的 `.env`、`config.yml`、`providers.yml`、`whitelist.yml`、`message.yml`、`tools.yml`、`functions.yml` 与插件 JAR 不在文件树内；
- `workspace/config.yml` 只是普通 Workspace 资料，不会因名称与父目录配置相同而被误判；
- read/list/grep 受字符数、结果数、深度和超时限制。

这降低了 Prompt injection 探索配置和凭据的可达性，但 Workspace 自身仍是模型输入。服主应把其中第三方内容当作可影响 Agent 行为的文档进行审核。

## JavaScript 沙箱

每个 Function invocation 使用独立 Graal Context，并禁用：

- Java/Host class 与反射访问；
- 文件、网络等 IO；
- 环境变量、native access、进程、线程；
- Polyglot bindings 与跨语言能力；
- 动态 eval 和 `load`、`print`、`console` 等宿主入口。

唯一宿主桥是 `api.invoke`，action 仅允许 `approval.request`、`command.dispatch`、`native_tool.call`，并逐次映射到声明 capability。`native_tool.call.call_function` 永久禁止，避免 Function 递归和间接权限组合。

同步段、总工作流、操作总数、并发数、待审批数、源码和结果结构都有上限。工作流取消时，尚未完成的交互和操作会收到取消信号；已经被外部服务端接收的副作用无法通用回滚。

## 配置和凭据

四个控制面文件通过固定路径读取，拒绝符号链接和不安全文件类型。YAML 解析拒绝重复 key、alias、merge、自定义 tag、未知字段和超限结构。

`.env` 不进入发行 JAR，也不位于 Workspace。Provider 密钥用标准 Bearer header 发送；日志和错误展示不得包含凭据或完整敏感响应。建议：

- 优先用进程环境，其次用权限为 `0600` 的 `.env`；
- API key 只授予必要 Provider 能力并定期轮换；
- 限制谁能读取插件数据目录和服务器日志；
- 不把 key 写入 `providers.yml`、Skill、AGENTS、命令或玩家文案；
- 发布前扫描源码、资源、构建产物和 Git 历史。

## 原子快照与并发

- 控制面联合校验后一次发布；失败不产生混合新旧配置。
- 每个 Turn 固定使用开始时的配置、Provider/模型、Tool 和 Function 快照。
- 模型切换只影响后续 Turn。
- 公共 Session 同一时刻只有一个活动 Turn，避免公共历史和副作用交错。
- `/mineclaw compact` 在活动 Turn 时排队，等待该 Turn 完整落入 Session 后执行。
- 自动/手动压缩只有在完整摘要成功且 Session generation 未变化时原子发布；失败保留原历史。

## MiniMessage 与交互

模型回复可使用完整 MiniMessage 颜色标签，包括命名色和十六进制颜色。Mineclaw 不允许模型提供 click/hover 事件；审批和 Function 交互的可点击组件只由代码根据 `message.yml` 模板创建。

服主配置的 message 模板仍应视为受信任配置。不要把未经转义的外部字符串改造成可执行 click/hover 标签；动态玩家、命令、意图和选项通过组件占位符进入预定义布局。

## 不在保证范围内

- Mineclaw 不能把任意服务端命令变成事务；多条命令中途失败时没有通用回滚。
- Provider、第三方插件和 Minecraft 本身的漏洞不由 Mineclaw 沙箱消除。
- 服主主动写入 Reviewed Function 的危险命令属于已授权代码；白名单不会替其兜底。
- Session 当前只在内存中，重启不会恢复上下文或待审批。
- 公屏玩家能看见最终回复；不要要求 Agent 在公共 Session 处理私密数据。
- Markdown 可以诱导模型，但不能自行获得 runtime capability。Prompt 约束也不能代替权限与代码审核。

## 上线前最小安全检查

1. 保持 `whitelist.yml` 规则锚定、具体，逐条用允许与拒绝样本测试。
2. 审核每个 Function 的参数边界、身份来源、命令拼接、审批顺序和失败短路。
3. 用 `/mineclaw tools validate` 与 `/mineclaw functions validate` 检查目录。
4. 演练拒绝、超时、掉线、命令不存在、Provider 429/5xx 和压缩失败。
5. 确认 `.env` 权限、日志访问和备份范围。
6. 从非 OP 玩家和目标玩家两种身份验证权限与交互。
7. 检查最终话术没有把 dispatched 误报为 executed。
