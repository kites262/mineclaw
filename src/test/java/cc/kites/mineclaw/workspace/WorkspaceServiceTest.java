package cc.kites.mineclaw.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceServiceTest {
    @Test
    void allowsASymlinkedWorkspaceRootWhileStillRejectingFinalFileLinks(@TempDir Path directory)
            throws Exception {
        Path realWorkspace = Files.createDirectories(directory.resolve("persistent-workspace"));
        Path workspaceLink = directory.resolve("workspace-link");
        Files.createSymbolicLink(workspaceLink, realWorkspace);
        WorkspaceService service = new WorkspaceService(workspaceLink,
                "---\nname: Linked\n---\ncontent", ignored -> { });

        AgentDocument document = service.readAgentDocument(true, 1_000, "Fallback");

        assertThat(document.displayName()).isEqualTo("Linked");
        assertThat(document.content()).contains("content");
        assertThat(Files.isRegularFile(realWorkspace.resolve(WorkspaceService.AGENTS_FILE_NAME))).isTrue();
    }

    @Test
    void rejectsAgentHardLinksToProtectedFiles(@TempDir Path directory) throws Exception {
        Path secret = directory.resolve("config.yml");
        Files.writeString(secret, "TOP_SECRET_VALUE", StandardCharsets.UTF_8);
        Files.createLink(directory.resolve(WorkspaceService.AGENTS_FILE_NAME), secret);
        WorkspaceService service = new WorkspaceService(directory, "unused", ignored -> { });

        assertThatThrownBy(() -> service.readAgentDocument(false, 1_000, "Fallback"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageNotContaining("TOP_SECRET_VALUE");
    }

    @Test
    void rejectsAgentSymlinksOutsideTheWorkspace(@TempDir Path directory) throws Exception {
        Path workspace = Files.createDirectories(directory.resolve("workspace"));
        Path outside = directory.resolve("outside.md");
        Files.writeString(outside, "TOP_SECRET_VALUE", StandardCharsets.UTF_8);
        Files.createSymbolicLink(workspace.resolve(WorkspaceService.AGENTS_FILE_NAME), outside);
        WorkspaceService service = new WorkspaceService(workspace, "unused", ignored -> { });

        assertThatThrownBy(() -> service.readAgentDocument(false, 1_000, "Fallback"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageNotContaining("TOP_SECRET_VALUE")
                .hasMessageNotContaining(outside.toString());
    }

    @Test
    void bundledAgentAndRuntimeReseedTemplateStayIdentical() throws Exception {
        try (var stream = WorkspaceServiceTest.class.getResourceAsStream("/AGENTS.md")) {
            assertThat(stream).isNotNull();
            assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo(WorkspaceService.DEFAULT_AGENT_TEMPLATE);
        }
    }

    @Test
    void seedsOnceThenHotReadsEveryRequest(@TempDir Path directory) throws Exception {
        String seed = "---\nname: Seeded\n---\n\n# Identity\nseed";
        WorkspaceService service = new WorkspaceService(directory, seed, ignored -> { });

        AgentDocument first = service.readAgentDocument(true, 1_000, "Config Name");
        assertThat(first.seeded()).isTrue();
        assertThat(first.displayName()).isEqualTo("Seeded");
        assertThat(first.content()).isEqualTo(seed);

        Files.writeString(service.agentsFile(), "---\ndisplay_name: Hot\n---\nchanged", StandardCharsets.UTF_8);
        AgentDocument second = service.readAgentDocument(true, 1_000, "Config Name");
        assertThat(second.seeded()).isFalse();
        assertThat(second.displayName()).isEqualTo("Hot");
        assertThat(second.content()).contains("changed");
    }

    @Test
    void displayNamePriorityIsNameThenDisplayNameThenHeadingThenIdentityThenDefault(@TempDir Path directory)
            throws Exception {
        WorkspaceService service = new WorkspaceService(directory, "unused", ignored -> { });

        Files.writeString(service.agentsFile(), """
                ---
                name: Primary
                display_name: Secondary
                ---
                # Heading
                """, StandardCharsets.UTF_8);
        assertThat(service.readAgentDocument(false, 1_000, "Identity").displayName()).isEqualTo("Primary");

        Files.writeString(service.agentsFile(), "---\ndisplay_name: Secondary\n---\n# Heading\n",
                StandardCharsets.UTF_8);
        assertThat(service.readAgentDocument(false, 1_000, "Identity").displayName()).isEqualTo("Secondary");

        Files.writeString(service.agentsFile(), "# Heading #\n", StandardCharsets.UTF_8);
        assertThat(service.readAgentDocument(false, 1_000, "Identity").displayName()).isEqualTo("Heading");

        Files.writeString(service.agentsFile(), "plain text\n", StandardCharsets.UTF_8);
        assertThat(service.readAgentDocument(false, 1_000, "Identity").displayName()).isEqualTo("Identity");
        assertThat(service.readAgentDocument(false, 1_000, "  ").displayName()).isEqualTo("Mineclaw");
    }

    @Test
    void truncatesInjectedTextLogsAndStillParsesNameFromFullSource(@TempDir Path directory) throws Exception {
        ArrayList<String> warnings = new ArrayList<>();
        WorkspaceService service = new WorkspaceService(directory, "unused", warnings::add);
        String source = "0123456789\n# Name After Limit\n";
        Files.writeString(service.agentsFile(), source, StandardCharsets.UTF_8);

        AgentDocument document = service.readAgentDocument(false, 5, "Fallback");

        assertThat(document.content()).isEqualTo("01234");
        assertThat(document.sourceLength()).isEqualTo(source.length());
        assertThat(document.truncated()).isTrue();
        assertThat(document.displayName()).isEqualTo("Name After Limit");
        assertThat(warnings).singleElement().asString().contains("workspace.max_chars.agents");
    }

    @Test
    void disabledSeedingLeavesWorkspaceUntouchedAndUsesFallback(@TempDir Path directory) throws Exception {
        Path missingRoot = directory.resolve("missing");
        WorkspaceService service = new WorkspaceService(missingRoot, "unused", ignored -> { });

        AgentDocument document = service.readAgentDocument(false, 100, "Config Name");

        assertThat(document.content()).isEmpty();
        assertThat(document.displayName()).isEqualTo("Config Name");
        assertThat(document.seeded()).isFalse();
        assertThat(Files.exists(missingRoot)).isFalse();
    }

    @Test
    void displayNameIsSingleLineControlFreeAndBounded(@TempDir Path directory) throws Exception {
        WorkspaceService service = new WorkspaceService(directory, "unused", ignored -> { });
        Files.writeString(service.agentsFile(), "---\nname: \"Boss\\nInjected\"\n---\n",
                StandardCharsets.UTF_8);

        assertThat(service.readAgentDocument(false, 1_000, "fallback").displayName())
                .isEqualTo("Boss Injected");

        Files.writeString(service.agentsFile(), "plain", StandardCharsets.UTF_8);
        assertThat(service.readAgentDocument(false, 1_000, "x".repeat(80)).displayName())
                .hasSize(64)
                .doesNotContain("\n", "\r");
    }

    @Test
    void hugeAgentFileUsesABoundedDiscoveryPrefix(@TempDir Path directory) throws Exception {
        WorkspaceService service = new WorkspaceService(directory, "unused", ignored -> { });
        String source = "x".repeat(70_000) + "\n# Heading Outside Bounded Prefix\n";
        Files.writeString(service.agentsFile(), source, StandardCharsets.UTF_8);

        AgentDocument document = service.readAgentDocument(false, 5, "Fallback");

        assertThat(document.content()).isEqualTo("xxxxx");
        assertThat(document.truncated()).isTrue();
        assertThat(document.sourceLength()).isLessThan(source.length());
        assertThat(document.displayName()).isEqualTo("Fallback");
    }
}
