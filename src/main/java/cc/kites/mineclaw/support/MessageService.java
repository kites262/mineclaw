package cc.kites.mineclaw.support;

import cc.kites.mineclaw.interaction.InteractionManager;
import cc.kites.mineclaw.workspace.WorkspacePathSecurity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Hot-reads player-visible message templates and falls back to bundled Chinese defaults. */
public final class MessageService {
    private static final MiniMessage MINI = MiniMessage.builder().tags(StandardTags.color()).build();
    private static final String MESSAGE_FILE_NAME = "message.yml";

    private final Path root;
    private final Path file;
    private final WorkspacePathSecurity pathSecurity;
    private final Map<String, String> defaults;
    private final ResourceSeeder resourceSeeder;
    private final Consumer<String> warningSink;
    private final Map<String, Boolean> warned = new ConcurrentHashMap<>();

    public MessageService(JavaPlugin plugin) {
        this(dataFolder(plugin), loadBundled(plugin), () -> plugin.saveResource(MESSAGE_FILE_NAME, false),
                plugin.getLogger()::warning);
    }

    MessageService(Path root, Map<String, String> defaults, ResourceSeeder resourceSeeder,
                   Consumer<String> warningSink) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.file = this.root.resolve(MESSAGE_FILE_NAME);
        this.pathSecurity = new WorkspacePathSecurity(this.root);
        this.defaults = Map.copyOf(Objects.requireNonNull(defaults, "defaults"));
        this.resourceSeeder = Objects.requireNonNull(resourceSeeder, "resourceSeeder");
        this.warningSink = Objects.requireNonNull(warningSink, "warningSink");
    }

    public void seed() throws IOException {
        Files.createDirectories(root);
        pathSecurity.requireFixedSeedTarget(file, MESSAGE_FILE_NAME);
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
            resourceSeeder.seed();
        }
        pathSecurity.requireFixedReadable(file, MESSAGE_FILE_NAME);
    }

    public Component render(String key) {
        return render(key, Map.of());
    }

    public Component render(String key, Map<String, String> values) {
        return render(key, values, Map.of());
    }

    /** Renders model reply Markdown through a component placeholder, never through MiniMessage source text. */
    public Component renderReply(String displayName, String reply) {
        return render("reply_prefix", Map.of("name", displayName),
                Map.of("reply", SafeMarkdown.render(reply)));
    }

    /** Builds a private approval card whose last line contains token-bound accept/reject buttons. */
    public Component renderApprovalPrompt(Map<String, String> values) {
        Objects.requireNonNull(values, "values");
        String token = Objects.requireNonNull(values.get("token"), "approval token");
        ApprovalPrompt.Controls controls = ApprovalPrompt.controls(
                token,
                render("approve_accept_button"),
                render("approve_reject_button"),
                render("approve_accept_hover"),
                render("approve_reject_hover"));
        Component buttons = render("approve_buttons", values, Map.of(
                "accept", controls.accept(),
                "reject", controls.reject()));
        return render("approve_layout", values, Map.of(
                "separator", render("approve_separator", values),
                "prefix", render("approve_prefix", values),
                "title", render("approve_title", values),
                "requester", render("approve_requester", values),
                "intent", render("approve_intent", values),
                "command", render("approve_command", values),
                "player", render("approve_player", values),
                "expires", render("approve_expires", values),
                "buttons", buttons));
    }

    /** Renders script text literally while message.yml controls layout around Java-bound actions. */
    public Component renderInteractionPrompt(String token, InteractionManager.Interaction interaction) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(interaction, "interaction");
        Component prefix = render("interaction_prefix");
        Component separator = render("interaction_separator");
        Component title = render("interaction_title", Map.of(), Map.of(
                "title", Component.text(interaction.title())));
        Component message = render("interaction_message", Map.of(), Map.of(
                "message", Component.text(interaction.message())));

        if (interaction instanceof InteractionManager.Confirm) {
            InteractionPrompt.Controls controls = InteractionPrompt.confirmControls(
                    token,
                    render("interaction_accept_button"),
                    render("interaction_reject_button"),
                    render("interaction_accept_hover"),
                    render("interaction_reject_hover"));
            Component buttons = render("interaction_confirm_buttons", Map.of(), Map.of(
                    "accept", controls.accept(),
                    "reject", controls.reject()));
            return render("interaction_confirm_layout", Map.of(), Map.of(
                    "separator", separator,
                    "prefix", prefix,
                    "title", title,
                    "message", message,
                    "buttons", buttons));
        }
        if (interaction instanceof InteractionManager.Select select) {
            Component optionSeparator = render("interaction_select_option_separator", Map.of(), Map.of(
                    "prefix", prefix));
            var options = Component.text();
            for (int index = 0; index < select.options().size(); index++) {
                InteractionManager.Option option = select.options().get(index);
                Component literalLabel = Component.text(option.label());
                Component label = render("interaction_select_option", Map.of(), Map.of(
                        "label", literalLabel));
                Component hover = render("interaction_select_option_hover", Map.of(), Map.of(
                        "label", literalLabel));
                if (index > 0) {
                    options.append(optionSeparator);
                }
                options.append(InteractionPrompt.selectOption(token, option.id(), label, hover));
            }
            Component reject = InteractionPrompt.reject(
                    token,
                    render("interaction_reject_button"),
                    render("interaction_reject_hover"));
            return render("interaction_select_layout", Map.of(), Map.of(
                    "separator", separator,
                    "prefix", prefix,
                    "title", title,
                    "message", message,
                    "options", options.build(),
                    "reject", reject));
        }
        throw new IllegalArgumentException("unsupported interaction type " + interaction.getClass().getName());
    }

    private Component render(String key, Map<String, String> values, Map<String, Component> components) {
        String template = readTemplate(key);
        TagResolver.Builder placeholders = TagResolver.builder();
        values.forEach((name, value) -> placeholders.resolver(Placeholder.unparsed(name, value)));
        components.forEach((name, value) -> placeholders.resolver(Placeholder.component(name, value)));
        try {
            return MINI.deserialize(template, placeholders.build());
        } catch (RuntimeException exception) {
            warnOnce("template:" + key, "Invalid message template " + key + ": " + exception.getMessage());
            String fallback = defaults.getOrDefault(key, key);
            return MINI.deserialize(fallback, placeholders.build());
        }
    }

    String readTemplate(String key) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(pathSecurity.readFixedUtf8(file, MESSAGE_FILE_NAME));
            String value = yaml.getString(key);
            if (value != null) {
                return value;
            }
            warnOnce("missing:" + key, "message.yml is missing key: " + key);
        } catch (IOException | InvalidConfigurationException exception) {
            warnOnce("read", "Cannot safely read message.yml; using bundled defaults ("
                    + exception.getClass().getSimpleName() + ")");
        }
        return defaults.getOrDefault(key, key);
    }

    private void warnOnce(String key, String text) {
        if (warned.putIfAbsent(key, Boolean.TRUE) == null) {
            warningSink.accept(text);
        }
    }

    private static Path dataFolder(JavaPlugin plugin) {
        return Objects.requireNonNull(plugin, "plugin").getDataFolder().toPath();
    }

    private static Map<String, String> loadBundled(JavaPlugin plugin) {
        try (InputStream stream = plugin.getResource("message.yml")) {
            if (stream == null) {
                throw new IllegalStateException("Bundled message.yml is missing");
            }
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            LinkedHashMap<String, String> values = new LinkedHashMap<>();
            for (String key : yaml.getKeys(false)) {
                String value = yaml.getString(key);
                if (value != null) {
                    values.put(key, value);
                }
            }
            return Map.copyOf(values);
        } catch (IOException | InvalidConfigurationException exception) {
            throw new IllegalStateException("Cannot load bundled message.yml", exception);
        }
    }

    @FunctionalInterface
    interface ResourceSeeder {
        void seed() throws IOException;
    }
}
