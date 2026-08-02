package cc.kites.mineclaw.workspace;

import cc.kites.mineclaw.config.MineclawConfig;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Direct-from-disk AGENTS.md loading, optional seeding, truncation and display-name discovery. */
public final class WorkspaceService {
    public static final String AGENTS_FILE_NAME = "AGENTS.md";
    public static final String DEFAULT_DISPLAY_NAME = "Mineclaw";
    public static final String DEFAULT_AGENT_TEMPLATE = """
            ---
            name: Mineclaw
            ---

            # 身份

            **你是「Mineclaw」。** 这是你的固定名字与公屏展示名。

            - 「Mineclaw」是 AI 助手，**不是** Minecraft 玩家，也不是任何玩家的账号名或 UUID。
            - 对话里出现的其他名字才可能是玩家；称呼玩家或填写 `run_command.player` 时，只使用 `online_players` 返回的准确账号名。
            - **禁止**把「Mineclaw」当成玩家：不要写入 `player`，不要对「Mineclaw」执行玩家操作，也不要把它与当前说话的玩家混淆。
            - 不要虚构玩家身份、在线状态、服务器状态、可用能力或操作结果。

            # 表达方式

            友善、直接、简洁。所有回复适合公共聊天阅读，不刷屏；需要强调时仅使用 `**加粗**`，不要使用其他 Markdown。

            # 请求处理流程

            ## 1. 查找能力文档

            请求涉及服务器规则、玩法、文件或执行操作时，先检查 `skills/` 中是否有匹配的能力文档（Skill）：

            1. 使用 `list` 或 `grep` 查找候选 Skill。
            2. 使用 `read` 阅读匹配 Skill 的完整原文。
            3. 找到匹配 Skill 后，遵循其中的步骤、参数和限制。
            4. 确认 Workspace 没有相应文档后，才说明当前未提供该能力。

            不要根据常识、插件印象或工具名称猜测命令、服务器能力和规则。普通寒暄或无需服务器事实即可回答的问题不必检索。

            ## 2. 获取必要事实

            - 环境问题需要事实时，再调用 `look_block`、`feet_block` 或 `inventory`。
            - 需要确认当前在线玩家的准确账号名时，使用 `online_players`，不要从昵称或上下文猜测。
            - 文件工具只能访问当前 Workspace（工作区）。`config.yml` 与 `.env` 会在 `list` 中显示为 `protected`，但 `read` 只返回保护提示，`grep` 会始终跳过；不要尝试读取、搜索、编辑、覆盖、移动、删除或绕过路径保护。
            - 无法从请求或可靠上下文确认准确账号名或 UUID 时，不要猜测或执行，应请玩家确认。

            ## 3. 执行命令

            - 调用 `run_command` 前先阅读匹配的任务 Skill；没有明确能力文档时不要自行发明命令。
            - `intent` 会作为“操作内容”展示给确认玩家；请用简短自然语言直接说清要做什么、由谁执行以及涉及谁。
            - `player` 表示实际执行命令的在线 Minecraft 玩家；涉及当前请求者或其他在线玩家时，先用 `online_players` 核对并使用返回的精确账号名。控制台执行时必须显式设为 `null`。
            - AI 展示名「Mineclaw」、昵称或自然语言称呼都不能作为 `player`。指定玩家时使用准确账号名或 UUID。
            - 区分命令执行者与命令目标：执行者写入 `player`，目标对象按能力文档写入 `command` 参数。
            - **选择执行身份（Skill 未另作强制时）**：
              - **玩家自身副作用**（传送、设点、对自身生效、依赖玩家权限/位置/背包）→ 用对话玩家 `player` 分发。
              - **控制台**只在任务 Skill 明确要求且控制台白名单允许时使用（`player: null`）；不要仅因为操作是查询或公共操作就自行切换到控制台。
            - 不得利用命名空间、大小写、空格、别名或间接命令绕过白名单和审批。
            - 以其他玩家身份执行时会等待该玩家审批；`pending_approval` 只表示等待，绝不表示已经执行。

            ## 4. 处理结果

            - 判断 `run_command` 时必须同时读取 `status`、`dispatch_status` 与 `execution_result`；不要只看顶层状态。
            - `status: dispatched` / `dispatch_status: accepted` 只表示命令已提交给命令系统。只要 `execution_result` 是 `unknown`，就只能说“已分发”，绝不能声称操作已经完成或生效。
            - **反馈可见性**：
              - **控制台**（`player: null`）：`feedback` 可包含分发返回前同步产生的命令输出（例如在线玩家列表），可如实转述；仍不能仅凭这些输出断定所有副作用已生效。
              - **玩家分发**：不回传游戏内聊天或命令反馈，工具侧通常只能看到分发是否被接受，以及未找到、拒绝等分发结果，不能看到玩家屏幕上的具体结果文案。
            - `run_command` 返回待审批状态时，说明正在等待对应玩家批准。
            - 工具调用失败时，可根据明确错误调整参数后重试一次；再次失败后简短说明真实原因。
            - 尊重 denied、invalid、timeout 和 terminal_error，不得改写成成功或尝试绕过限制。
            """;

    private static final Pattern FIRST_HEADING = Pattern.compile("(?m)^#\\s+(.+?)\\s*$");
    private static final int DISPLAY_SCAN_CHARS = 64 * 1024;
    private static final int MAX_DISPLAY_NAME_CODE_POINTS = 64;

    private final Path root;
    private final Path agentsFile;
    private final WorkspacePathSecurity pathSecurity;
    private final String seedTemplate;
    private final Consumer<String> warningSink;

    public WorkspaceService(Path root, Logger logger) {
        this(root, DEFAULT_AGENT_TEMPLATE, Objects.requireNonNull(logger, "logger")::warning);
    }

    public WorkspaceService(Path root, String seedTemplate, Logger logger) {
        this(root, seedTemplate, Objects.requireNonNull(logger, "logger")::warning);
    }

    public WorkspaceService(Path root, String seedTemplate, Consumer<String> warningSink) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.agentsFile = this.root.resolve(AGENTS_FILE_NAME);
        this.pathSecurity = new WorkspacePathSecurity(this.root);
        this.seedTemplate = Objects.requireNonNull(seedTemplate, "seedTemplate");
        this.warningSink = Objects.requireNonNull(warningSink, "warningSink");
    }

    public AgentDocument readAgentDocument(MineclawConfig config) throws IOException {
        Objects.requireNonNull(config, "config");
        return readAgentDocument(config.workspace(), config.identity().name());
    }

    public AgentDocument readAgentDocument(MineclawConfig.Workspace settings, String identityName)
            throws IOException {
        Objects.requireNonNull(settings, "settings");
        return readAgentDocument(settings.seedDefaults(), settings.maxChars().agents(), identityName);
    }

    /** Every invocation reads the current file; no AGENTS.md content is cached. */
    public AgentDocument readAgentDocument(boolean seedDefaults, int maxChars, String identityName)
            throws IOException {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be greater than zero");
        }

        boolean seeded = false;
        if (Files.notExists(agentsFile, LinkOption.NOFOLLOW_LINKS)) {
            if (!seedDefaults) {
                return new AgentDocument("", fallbackName(identityName), false, false, 0);
            }
            Files.createDirectories(root);
            pathSecurity.requireFixedSeedTarget(agentsFile, AGENTS_FILE_NAME);
            try {
                Files.writeString(agentsFile, seedTemplate, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                seeded = true;
            } catch (FileAlreadyExistsException ignored) {
                // A concurrent request seeded it first. Read that complete file below.
            }
        } else {
            pathSecurity.requireFixedSeedTarget(agentsFile, AGENTS_FILE_NAME);
        }

        SourcePrefix snapshot = readSourcePrefix(maxChars);
        String source = snapshot.text();
        String displayName = discoverDisplayName(source, identityName);
        boolean truncated = snapshot.observedLength() > maxChars;
        String injected = truncated ? source.substring(0, maxChars) : source;
        if (truncated) {
            warningSink.accept(AGENTS_FILE_NAME + " exceeds workspace.max_chars.agents ("
                    + (snapshot.capped() ? "at least " : "") + snapshot.observedLength()
                    + " > " + maxChars + "); truncating for this request");
        }
        return new AgentDocument(injected, displayName, seeded, truncated, snapshot.observedLength());
    }

    public AgentDocument loadAgentDocument(MineclawConfig config) throws IOException {
        return readAgentDocument(config);
    }

    public Path root() {
        return root;
    }

    public Path agentsFile() {
        return agentsFile;
    }

    static String discoverDisplayName(String source, String identityName) {
        String fromFrontMatter = cleanDisplayName(frontMatterName(source));
        if (!fromFrontMatter.isEmpty()) {
            return fromFrontMatter;
        }

        Matcher heading = FIRST_HEADING.matcher(source);
        if (heading.find()) {
            String value = cleanDisplayName(
                    heading.group(1).replaceFirst("\\s+#+\\s*$", ""));
            if (!value.isEmpty()) {
                return value;
            }
        }
        return fallbackName(identityName);
    }

    private static String frontMatterName(String source) {
        int contentStart = source.startsWith("\uFEFF") ? 1 : 0;
        int firstLineEnd = lineEnd(source, contentStart);
        if (!source.substring(contentStart, firstLineEnd).trim().equals("---")) {
            return null;
        }

        int frontMatterStart = skipLineBreak(source, firstLineEnd);
        int cursor = frontMatterStart;
        int frontMatterEnd = -1;
        while (cursor <= source.length()) {
            int end = lineEnd(source, cursor);
            if (source.substring(cursor, end).trim().equals("---")) {
                frontMatterEnd = cursor;
                break;
            }
            if (end == source.length()) {
                break;
            }
            cursor = skipLineBreak(source, end);
        }
        if (frontMatterEnd < 0) {
            return null;
        }

        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(10);
        options.setCodePointLimit(Math.max(1_024, frontMatterEnd - frontMatterStart + 1));
        Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(options)).load(source.substring(frontMatterStart, frontMatterEnd));
        } catch (YAMLException exception) {
            return null;
        }
        if (!(loaded instanceof Map<?, ?> metadata)) {
            return null;
        }
        String name = scalar(metadata.get("name"));
        if (name != null) {
            return name;
        }
        return scalar(metadata.get("display_name"));
    }

    private static String scalar(Object value) {
        if (!(value instanceof String string)) {
            return null;
        }
        String trimmed = string.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String fallbackName(String identityName) {
        String cleaned = cleanDisplayName(identityName);
        if (!cleaned.isEmpty()) {
            return cleaned;
        }
        return DEFAULT_DISPLAY_NAME;
    }

    private SourcePrefix readSourcePrefix(int maxChars) throws IOException {
        int retainedLimit = (int) Math.min(Integer.MAX_VALUE,
                Math.max((long) DISPLAY_SCAN_CHARS, (long) maxChars + 1L));
        StringBuilder retained = new StringBuilder(Math.min(retainedLimit, 16 * 1024));
        boolean capped = false;
        try (Reader reader = pathSecurity.openFixedUtf8(agentsFile, AGENTS_FILE_NAME)) {
            char[] buffer = new char[4 * 1024];
            while (retained.length() < retainedLimit) {
                int count = reader.read(buffer, 0, Math.min(buffer.length, retainedLimit - retained.length()));
                if (count < 0) {
                    break;
                }
                retained.append(buffer, 0, count);
            }
            if (retained.length() == retainedLimit && reader.read() >= 0) {
                capped = true;
            }
        }
        int observedLength = retained.length() + (capped ? 1 : 0);
        return new SourcePrefix(retained.toString(), observedLength, capped);
    }

    private static String cleanDisplayName(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        boolean pendingSpace = false;
        int codePoints = 0;
        for (int offset = 0; offset < value.length() && codePoints < MAX_DISPLAY_NAME_CODE_POINTS;) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            int type = Character.getType(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
                    || Character.isISOControl(codePoint) || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) {
                pendingSpace = result.length() > 0;
                continue;
            }
            if (pendingSpace) {
                result.append(' ');
                codePoints++;
                pendingSpace = false;
                if (codePoints >= MAX_DISPLAY_NAME_CODE_POINTS) {
                    break;
                }
            }
            result.appendCodePoint(codePoint);
            codePoints++;
        }
        return result.toString();
    }

    private record SourcePrefix(String text, int observedLength, boolean capped) { }

    private static int lineEnd(String source, int start) {
        int newline = source.indexOf('\n', start);
        if (newline < 0) {
            return source.length();
        }
        int end = newline;
        if (end > start && source.charAt(end - 1) == '\r') {
            end--;
        }
        return end;
    }

    private static int skipLineBreak(String source, int lineEnd) {
        int cursor = lineEnd;
        if (cursor < source.length() && source.charAt(cursor) == '\r') {
            cursor++;
        }
        if (cursor < source.length() && source.charAt(cursor) == '\n') {
            cursor++;
        }
        return cursor;
    }
}
