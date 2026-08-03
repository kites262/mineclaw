package cc.kites.mineclaw.tool;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceFileToolsTest {
    private static final WorkspaceFileTools.Limits LIMITS =
            new WorkspaceFileTools.Limits(64, 20, 4, 2_000L);

    @TempDir
    Path root;

    @Test
    void workspaceRootNaturallyExcludesSiblingConfigurationFiles() throws IOException {
        Files.writeString(root.resolve("config.yml"), "api_key: config-secret");
        Files.writeString(root.resolve(".env"), "MINECLAW_API_KEY=env-secret");
        Files.writeString(root.resolve("functions.yml"), "on_call: function-secret");
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        Files.createDirectories(workspace.resolve("skills"));
        Files.writeString(workspace.resolve("AGENTS.md"), "agent instructions");
        Files.writeString(workspace.resolve("config.yml"), "ordinary workspace document");
        Files.writeString(workspace.resolve("skills/guide.md"), "needle here");
        WorkspaceFileTools tools = new WorkspaceFileTools(workspace);

        JsonObject list = new JsonObject();
        list.addProperty("depth", 3);
        ToolResult listed = tools.list(list, LIMITS);
        assertThat(listed.json())
                .contains("AGENTS.md", "config.yml", "skills/guide.md")
                .doesNotContain("config-secret", "env-secret", "function-secret", "protected");
        assertThat(findListedItem(listed, "AGENTS.md").keySet())
                .containsExactlyInAnyOrder("path", "type", "size");

        JsonObject workspaceConfig = new JsonObject();
        workspaceConfig.addProperty("path", "config.yml");
        assertThat(tools.read(workspaceConfig, LIMITS).output().get("content").getAsString())
                .isEqualTo("ordinary workspace document");

        JsonObject read = new JsonObject();
        read.addProperty("path", "../config.yml");
        assertThat(tools.read(read, LIMITS).status()).isEqualTo("denied");

        JsonObject grep = new JsonObject();
        grep.addProperty("pattern", "secret");
        assertThat(tools.grep(grep, LIMITS).output().getAsJsonArray("matches")).isEmpty();
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
