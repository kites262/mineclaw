package cc.kites.mineclaw.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageServiceTest {
    @Test
    void seedsAndHotReadsAnOrdinaryMessageFile(@TempDir Path directory) throws Exception {
        ArrayList<String> warnings = new ArrayList<>();
        MessageService service = service(directory, warnings);

        service.seed();

        assertThat(service.readTemplate("sample")).isEqualTo("live");
        assertThat(warnings).isEmpty();
    }

    @Test
    void seedRejectsAHardLinkToAProtectedFileWithoutEchoingIt(@TempDir Path directory)
            throws Exception {
        Path secret = directory.resolve(".env");
        Files.writeString(secret, "TOP_SECRET_VALUE", StandardCharsets.UTF_8);
        Files.createLink(directory.resolve("message.yml"), secret);
        MessageService service = service(directory, new ArrayList<>());

        assertThatThrownBy(service::seed)
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageNotContaining("TOP_SECRET_VALUE");
    }

    @Test
    void unsafeRuntimeReplacementFallsBackWithoutReadingOrLoggingContent(@TempDir Path directory)
            throws Exception {
        Path workspace = Files.createDirectories(directory.resolve("workspace"));
        Path outside = directory.resolve("outside.yml");
        Files.writeString(outside, "sample: TOP_SECRET_VALUE", StandardCharsets.UTF_8);
        Files.createSymbolicLink(workspace.resolve("message.yml"), outside);
        ArrayList<String> warnings = new ArrayList<>();
        MessageService service = service(workspace, warnings);

        assertThat(service.readTemplate("sample")).isEqualTo("fallback");
        assertThat(warnings).singleElement().asString()
                .doesNotContain("TOP_SECRET_VALUE", outside.toString());
    }

    private static MessageService service(Path directory, ArrayList<String> warnings) {
        return new MessageService(directory, Map.of("sample", "fallback"),
                () -> Files.writeString(directory.resolve("message.yml"), "sample: live\n",
                        StandardCharsets.UTF_8),
                warnings::add);
    }
}
