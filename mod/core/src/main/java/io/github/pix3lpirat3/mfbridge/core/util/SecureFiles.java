package io.github.pix3lpirat3.mfbridge.core.util;

import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;

/**
 * Atomic, owner-only file writes. Permissions are best effort: a failure to
 * restrict them is logged, never thrown, so startup does not fail on exotic file systems.
 */
@AnyThread
public final class SecureFiles {
    private static final Set<PosixFilePermission> OWNER_RW =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private SecureFiles() {}

    /**
     * Writes {@code data} to {@code target} atomically: a temporary sibling is created, restricted to
     * the current user, filled, then moved over the target.
     *
     * @throws IOException when the file cannot be written or moved
     */
    public static void writeAtomically(Path target, byte[] data, boolean ownerOnly, Logger log) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = dir.resolve(target.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        Files.createFile(tmp);
        if (ownerOnly) {
            restrictToOwner(tmp, log);
        }
        Files.write(tmp, data, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        replace(tmp, target);
    }

    /**
     * Moves {@code source} over {@code target}, atomically where the file system supports it.
     *
     * @throws IOException when the move fails
     */
    public static void replace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Restricts a file to the current user: POSIX {@code 0600}, or on Windows an ACL with a single
     * allow entry for the owner. Never throws; failures are logged as warnings.
     */
    public static void restrictToOwner(Path file, Logger log) {
        try {
            PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
            if (posix != null) {
                posix.setPermissions(OWNER_RW);
                return;
            }
            AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
            if (acl != null) {
                UserPrincipal owner = acl.getOwner();
                AclEntry entry = AclEntry.newBuilder()
                        .setType(AclEntryType.ALLOW)
                        .setPrincipal(owner)
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                        .build();
                acl.setAcl(List.of(entry));
            }
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            log.warn("Cannot restrict permissions of {}: {}", file, e.toString());
        }
    }
}
