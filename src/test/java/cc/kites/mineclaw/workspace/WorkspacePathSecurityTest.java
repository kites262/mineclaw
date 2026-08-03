package cc.kites.mineclaw.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspacePathSecurityTest {
    @Test
    void trustedFixedLoadersCanReadTheExactProtectedResource(@TempDir Path directory)
            throws Exception {
        Path functions = directory.resolve("functions.yml");
        Files.writeString(functions, "schema: 1\napi_version: 1\nfunctions: []\n");
        WorkspacePathSecurity security = new WorkspacePathSecurity(directory);

        assertThat(security.readFixedUtf8(functions, "functions.yml"))
                .contains("functions: []");
    }

    @Test
    void trustedFixedLoaderRejectsHardLinkConfusionWithAnotherProtectedResource(
            @TempDir Path directory) throws Exception {
        Path config = directory.resolve("config.yml");
        Path functions = directory.resolve("functions.yml");
        Files.writeString(config, "TOP_SECRET_VALUE");
        try {
            Files.createLink(functions, config);
        } catch (UnsupportedOperationException exception) {
            return;
        }
        WorkspacePathSecurity security = new WorkspacePathSecurity(directory);

        assertThatThrownBy(() -> security.readFixedUtf8(functions, "functions.yml"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageNotContaining("TOP_SECRET_VALUE");
    }
}
