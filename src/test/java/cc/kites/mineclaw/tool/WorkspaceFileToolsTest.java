package cc.kites.mineclaw.tool;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceFileToolsTest {
    private static final WorkspaceFileTools.Limits LIMITS =
            new WorkspaceFileTools.Limits(64, 20, 4, 2_000L);

    @TempDir
    Path root;

    @Test
    void listsSensitiveFilesButProtectsTheirContentsFromReadAndGrep() throws IOException {
        Files.writeString(root.resolve("config.yml"), "api_key: config-secret");
        Files.writeString(root.resolve(".env"), "MINECLAW_API_KEY=env-secret");
        Files.createDirectories(root.resolve("skills"));
        Files.writeString(root.resolve("skills/guide.md"), "name: guide\nneedle here\n");
        WorkspaceFileTools tools = new WorkspaceFileTools(root);

        for (String path : List.of("./config.yml", "secrets/../.env", ".env/nested")) {
            JsonObject read = new JsonObject();
            read.addProperty("path", path);
            ToolResult protectedResult = tools.read(read, LIMITS);
            assertThat(protectedResult.status()).isEqualTo("denied");
            assertThat(protectedResult.output().get("status").getAsString()).isEqualTo("protected");
            assertThat(protectedResult.output().get("content").getAsString())
                    .isEqualTo(WorkspaceFileTools.PROTECTED_CONTENT);
        }

        JsonObject list = new JsonObject();
        list.addProperty("depth", 3);
        ToolResult listResult = tools.list(list, LIMITS);
        assertThat(listResult.json())
                .contains("config.yml", ".env", "skills/guide.md")
                .doesNotContain("config-secret", "env-secret");
        for (String path : List.of("config.yml", ".env")) {
            JsonObject item = findListedItem(listResult, path);
            assertThat(item.get("protected").getAsBoolean()).isTrue();
            assertThat(item.has("size")).isFalse();
        }
        assertThat(findListedItem(listResult, "skills/guide.md").get("protected").getAsBoolean()).isFalse();

        JsonObject grep = new JsonObject();
        grep.addProperty("pattern", "secret");
        assertThat(tools.grep(grep, LIMITS).output().getAsJsonArray("matches")).isEmpty();

        for (String path : List.of("config.yml", ".env", ".env/nested")) {
            grep.addProperty("path", path);
            ToolResult result = tools.grep(grep, LIMITS);
            assertThat(result.status()).isEqualTo("ok");
            assertThat(result.output().getAsJsonArray("matches")).isEmpty();
        }

        Files.delete(root.resolve("config.yml"));
        Files.delete(root.resolve(".env"));
        for (String path : List.of("config.yml", ".env")) {
            JsonObject read = new JsonObject();
            read.addProperty("path", path);
            assertThat(tools.read(read, LIMITS).output().get("status").getAsString()).isEqualTo("protected");
        }
    }

    @Test
    void protectsSensitiveFilesThroughSymbolicAndHardLinkAliases() throws IOException {
        Path config = root.resolve("config.yml");
        Path environment = root.resolve(".env");
        Files.writeString(config, "shared-secret");
        Files.writeString(environment, "shared-secret");
        Path configSymlink = root.resolve("config-alias.yml");
        Path environmentHardLink = root.resolve("environment-copy");
        try {
            Files.createSymbolicLink(configSymlink, config.getFileName());
            Files.createLink(environmentHardLink, environment);
        } catch (UnsupportedOperationException exception) {
            return;
        }
        WorkspaceFileTools tools = new WorkspaceFileTools(root);

        for (String path : List.of("config-alias.yml", "environment-copy")) {
            JsonObject read = new JsonObject();
            read.addProperty("path", path);
            assertThat(tools.read(read, LIMITS).output().get("status").getAsString()).isEqualTo("protected");

            JsonObject grep = new JsonObject();
            grep.addProperty("path", path);
            grep.addProperty("pattern", "shared-secret");
            assertThat(tools.grep(grep, LIMITS).output().getAsJsonArray("matches")).isEmpty();
        }

        JsonObject grepRoot = new JsonObject();
        grepRoot.addProperty("pattern", "shared-secret");
        assertThat(tools.grep(grepRoot, LIMITS).output().getAsJsonArray("matches")).isEmpty();
    }

    @Test
    void protectsOnlyExactSensitiveFiles() throws IOException {
        Files.writeString(root.resolve("config.yml.example"), "public config example");
        Files.writeString(root.resolve(".env.example"), "public env example");
        WorkspaceFileTools tools = new WorkspaceFileTools(root);

        for (String path : List.of("config.yml.example", ".env.example")) {
            JsonObject read = new JsonObject();
            read.addProperty("path", path);
            assertThat(tools.read(read, LIMITS).output().get("content").getAsString()).contains("example");
        }
    }

    @Test
    void mutationGuardRejectsMissingNamesAliasesAndContainingPaths() throws IOException {
        Path config = root.resolve("config.yml");
        Files.writeString(config, "secret");
        Path hardLink = root.resolve("hard-link");
        Path rootAlias = root.resolve("root-alias");
        try {
            Files.createLink(hardLink, config);
            Files.createSymbolicLink(rootAlias, Path.of("."));
        } catch (UnsupportedOperationException exception) {
            return;
        }
        WorkspaceFileTools tools = new WorkspaceFileTools(root);

        for (String path : List.of("config.yml", "./.env", "missing/../.env", "hard-link",
                "root-alias/.env", ".env/nested", "")) {
            assertThatThrownBy(() -> tools.requireMutationAllowed(path))
                    .as("mutation path %s", path)
                    .isInstanceOf(AccessDeniedException.class);
        }
        assertThatThrownBy(() -> tools.requireMutationAllowed("../outside"))
                .isInstanceOf(AccessDeniedException.class);

        assertThatCode(() -> tools.requireMutationAllowed("skills/new.md")).doesNotThrowAnyException();
        Files.writeString(root.resolve("ordinary.txt"), "ordinary");
        assertThatCode(() -> tools.requireMutationAllowed("ordinary.txt")).doesNotThrowAnyException();
    }

    @Test
    void mutationGuardRejectsParentsOfProtectedSymlinkTargets() throws IOException {
        Path configTarget = root.resolve("private/config.actual");
        Path environmentTarget = root.resolve("secrets/environment.actual");
        Files.createDirectories(configTarget.getParent());
        Files.createDirectories(environmentTarget.getParent());
        Files.writeString(configTarget, "config-secret");
        Files.writeString(environmentTarget, "environment-secret");
        try {
            Files.createSymbolicLink(root.resolve("config.yml"), root.relativize(configTarget));
            Files.createSymbolicLink(root.resolve(".env"), root.relativize(environmentTarget));
        } catch (UnsupportedOperationException exception) {
            return;
        }
        WorkspaceFileTools tools = new WorkspaceFileTools(root);

        for (String path : List.of("private", "private/config.actual",
                "secrets", "secrets/environment.actual")) {
            assertThatThrownBy(() -> tools.requireMutationAllowed(path))
                    .as("mutation path %s", path)
                    .isInstanceOf(AccessDeniedException.class);
        }
        assertThatCode(() -> tools.requireMutationAllowed("private/unrelated.txt"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsTraversalAndSymlinkEscape() throws IOException {
        Path outside = Files.createTempFile("mineclaw-outside", ".txt");
        Files.writeString(outside, "private");
        WorkspaceFileTools tools = new WorkspaceFileTools(root);

        JsonObject traversal = new JsonObject();
        traversal.addProperty("path", "../" + outside.getFileName());
        assertThat(tools.read(traversal, LIMITS).status()).isEqualTo("denied");

        Path link = root.resolve("escape.txt");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException exception) {
            return;
        }
        JsonObject symlink = new JsonObject();
        symlink.addProperty("path", "escape.txt");
        assertThat(tools.read(symlink, LIMITS).status()).isEqualTo("denied");
    }

    @Test
    void readsOffsetsAndGrepsLiteralTextWithContext() throws IOException {
        Files.writeString(root.resolve("notes.txt"), "zero\none\na.b\nthree\n");
        WorkspaceFileTools tools = new WorkspaceFileTools(root);

        JsonObject read = new JsonObject();
        read.addProperty("path", "notes.txt");
        read.addProperty("offset", 5);
        read.addProperty("max_chars", 3);
        ToolResult readResult = tools.read(read, LIMITS);
        assertThat(readResult.output().get("content").getAsString()).isEqualTo("one");
        assertThat(readResult.output().get("truncated").getAsBoolean()).isTrue();

        JsonObject grep = new JsonObject();
        grep.addProperty("pattern", ".");
        grep.addProperty("context_lines", 1);
        ToolResult grepResult = tools.grep(grep, LIMITS);
        assertThat(grepResult.output().getAsJsonArray("matches")).hasSize(1);
        JsonObject match = grepResult.output().getAsJsonArray("matches").get(0).getAsJsonObject();
        assertThat(match.get("line").getAsInt()).isEqualTo(3);
        assertThat(match.getAsJsonArray("context_before").get(0).getAsString()).isEqualTo("one");
        assertThat(match.getAsJsonArray("context_after").get(0).getAsString()).isEqualTo("three");
    }

    @Test
    void capsGrepContextLines() throws IOException {
        Files.writeString(root.resolve("long.txt"), "needle\n" + "x".repeat(2_000) + "\n");
        WorkspaceFileTools tools = new WorkspaceFileTools(root);
        JsonObject grep = new JsonObject();
        grep.addProperty("pattern", "needle");
        grep.addProperty("context_lines", 1);

        JsonObject match = tools.grep(grep, LIMITS).output().getAsJsonArray("matches")
                .get(0).getAsJsonObject();
        assertThat(match.getAsJsonArray("context_after").get(0).getAsString()).hasSize(1_024);
    }

    private static JsonObject findListedItem(ToolResult result, String path) {
        return result.output().getAsJsonArray("items").asList().stream()
                .map(element -> element.getAsJsonObject())
                .filter(item -> item.get("path").getAsString().equals(path))
                .findFirst()
                .orElseThrow();
    }
}
