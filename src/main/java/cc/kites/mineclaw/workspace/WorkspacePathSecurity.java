package cc.kites.mineclaw.workspace;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

/** Shared fail-closed path policy for files in one Mineclaw Workspace. */
public final class WorkspacePathSecurity {
    private static final Set<String> PROTECTED_FILE_NAMES =
            Set.of("config.yml", ".env", "providers.yml", "whitelist.yml", "functions.yml");
    private static final String DENIED_PATH = "Workspace resource";
    private static final String DENIED_REASON = "unsafe workspace path";

    private final Path root;

    public WorkspacePathSecurity(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /** Opens one fixed Workspace resource without following a final symbolic link. */
    public Reader openFixedUtf8(Path path, String expectedName) throws IOException {
        Path candidate = requireFixedReadable(path, expectedName);
        SeekableByteChannel channel;
        try {
            channel = Files.newByteChannel(candidate,
                    Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
        } catch (IOException | SecurityException exception) {
            throw denied();
        }
        return new InputStreamReader(Channels.newInputStream(channel), StandardCharsets.UTF_8);
    }

    /** Validates that one fixed Workspace resource is a safe readable regular file. */
    public Path requireFixedReadable(Path path, String expectedName) throws IOException {
        Path candidate = requireFixedCandidate(path, expectedName);
        requireReadableRegularFile(candidate, expectedName);
        return candidate;
    }

    /** Reads one fixed Workspace resource after applying the same no-link policy. */
    public String readFixedUtf8(Path path, String expectedName) throws IOException {
        try (Reader reader = openFixedUtf8(path, expectedName)) {
            StringBuilder result = new StringBuilder();
            char[] buffer = new char[4 * 1024];
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                result.append(buffer, 0, count);
            }
            return result.toString();
        }
    }

    /** Validates a fixed seed destination before a CREATE_NEW write or resource copy. */
    public Path requireFixedSeedTarget(Path path, String expectedName) throws IOException {
        Path candidate = requireFixedCandidate(path, expectedName);
        if (Files.isSymbolicLink(candidate)) {
            throw denied();
        }
        if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            requireReadableRegularFile(candidate, expectedName);
        }
        return candidate;
    }

    private Path requireFixedCandidate(Path path, String expectedName) throws IOException {
        if (path == null || expectedName == null || expectedName.isBlank()) {
            throw denied();
        }
        final Path name;
        try {
            name = Path.of(expectedName);
        } catch (InvalidPathException exception) {
            throw denied();
        }
        if (name.isAbsolute() || name.getNameCount() != 1) {
            throw denied();
        }
        Path candidate = path.toAbsolutePath().normalize();
        Path expected = root.resolve(name).normalize();
        if (!candidate.equals(expected)) {
            throw denied();
        }

        Path realRoot;
        Path realParent;
        try {
            realRoot = root.toRealPath();
            realParent = candidate.getParent().toRealPath();
        } catch (IOException | SecurityException exception) {
            throw denied();
        }
        if (!realParent.equals(realRoot)) {
            throw denied();
        }
        return candidate;
    }

    private void requireReadableRegularFile(Path candidate, String expectedName) throws IOException {
        if (Files.isSymbolicLink(candidate)
                || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                || aliasesProtectedNameOtherThan(candidate, expectedName)) {
            throw denied();
        }
        try {
            Path realRoot = root.toRealPath();
            // The final component was rejected above if it was a symlink. Follow parent links here
            // so a deliberately symlinked Workspace root can still point at persistent storage.
            Path realFile = candidate.toRealPath();
            if (!realFile.startsWith(realRoot)) {
                throw denied();
            }
        } catch (AccessDeniedException exception) {
            throw exception;
        } catch (IOException | SecurityException exception) {
            throw denied();
        }
    }

    /**
     * A trusted fixed loader may read the protected file it names exactly. It must still reject a
     * hard-link collision with any other protected resource (for example functions.yml linked to
     * config.yml), while ordinary fixed resources such as AGENTS.md remain unable to alias any
     * protected file.
     */
    private boolean aliasesProtectedNameOtherThan(Path file, String expectedName) throws IOException {
        String allowedName = PROTECTED_FILE_NAMES.contains(expectedName) ? expectedName : null;
        Path normalized = file.toAbsolutePath().normalize();
        for (String name : PROTECTED_FILE_NAMES) {
            if (name.equals(allowedName)) {
                continue;
            }
            Path protectedFile = root.resolve(name);
            if (normalized.startsWith(protectedFile)) {
                return true;
            }
            if (Files.exists(protectedFile) && Files.exists(file)
                    && Files.isSameFile(file, protectedFile)) {
                return true;
            }
        }
        return false;
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException(DENIED_PATH, null, DENIED_REASON);
    }
}
