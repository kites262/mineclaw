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
    private static final Set<String> PROTECTED_FILE_NAMES = Set.of("config.yml", ".env");
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
        requireReadableRegularFile(candidate);
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
            requireReadableRegularFile(candidate);
        }
        return candidate;
    }

    /** Returns whether a normalized request names a protected file or one of its descendants. */
    public static boolean isProtectedRequest(String value) {
        if (value == null) {
            return false;
        }
        try {
            Path path = Path.of(value);
            if (path.isAbsolute()) {
                return false;
            }
            Path normalized = path.normalize();
            return PROTECTED_FILE_NAMES.stream().map(Path::of).anyMatch(normalized::startsWith);
        } catch (InvalidPathException exception) {
            return false;
        }
    }

    /** Detects direct names, descendants, real-path aliases and hard links to protected files. */
    public boolean isProtected(Path file) throws IOException {
        Path normalized = file.toAbsolutePath().normalize();
        for (String name : PROTECTED_FILE_NAMES) {
            Path protectedFile = root.resolve(name);
            if (normalized.startsWith(protectedFile)) {
                return true;
            }
            if (Files.exists(protectedFile) && Files.exists(file)) {
                Path realProtectedFile = protectedFile.toRealPath();
                Path realFile = file.toRealPath();
                if (realFile.startsWith(realProtectedFile) || Files.isSameFile(file, protectedFile)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Fails closed when a future edit, overwrite, move or delete operation would touch a protected
     * file. Mutation handlers must call this for every source and destination path.
     */
    public void requireMutationAllowed(String requestedPath) throws IOException {
        MutationTarget target;
        try {
            target = resolveMutationTarget(requestedPath);
        } catch (InvalidPathException exception) {
            throw denied();
        }
        Path realRoot = root.toRealPath();
        for (String name : PROTECTED_FILE_NAMES) {
            Path protectedFile = root.resolve(name);
            Path effectiveProtectedFile = realRoot.resolve(name);
            Path realProtectedFile = Files.exists(protectedFile)
                    ? protectedFile.toRealPath() : effectiveProtectedFile;
            boolean sameExistingFile = Files.exists(protectedFile) && Files.exists(target.candidate())
                    && Files.isSameFile(target.candidate(), protectedFile);
            if (overlaps(target.effective(), effectiveProtectedFile)
                    || overlaps(target.effective(), realProtectedFile)
                    || sameExistingFile) {
                throw denied();
            }
        }
    }

    private static boolean overlaps(Path mutationTarget, Path protectedTarget) {
        return mutationTarget.startsWith(protectedTarget) || protectedTarget.startsWith(mutationTarget);
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

    private void requireReadableRegularFile(Path candidate) throws IOException {
        if (Files.isSymbolicLink(candidate)
                || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                || isProtected(candidate)) {
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

    private MutationTarget resolveMutationTarget(String text) throws IOException {
        Path relative = text == null || text.isBlank() ? Path.of("") : Path.of(text);
        if (relative.isAbsolute()) {
            throw denied();
        }
        Path candidate = root.resolve(relative).normalize();
        if (!candidate.startsWith(root)) {
            throw denied();
        }

        Path existingAncestor = candidate;
        while (existingAncestor != null && Files.notExists(existingAncestor, LinkOption.NOFOLLOW_LINKS)) {
            existingAncestor = existingAncestor.getParent();
        }
        if (existingAncestor == null) {
            throw denied();
        }
        Path realRoot = root.toRealPath();
        Path realAncestor = existingAncestor.toRealPath();
        if (!realAncestor.startsWith(realRoot)) {
            throw denied();
        }
        Path effective = realAncestor.resolve(existingAncestor.relativize(candidate)).normalize();
        if (!effective.startsWith(realRoot)) {
            throw denied();
        }
        return new MutationTarget(candidate, effective);
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException(DENIED_PATH, null, DENIED_REASON);
    }

    private record MutationTarget(Path candidate, Path effective) {
    }
}
