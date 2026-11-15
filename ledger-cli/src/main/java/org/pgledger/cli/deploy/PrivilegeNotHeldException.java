package org.pgledger.cli.deploy;

import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Path;

/**
 * A secret or a folder that only an owner with the privileges to take ownership could change, and the process does not hold
 * them: it does not run in an elevated prompt ({@link WindowsPrivileges}). It is a {@link FileSystemException}, so the report of
 * a failure of the host ({@link HostFiles#failure}) names the path and the kind, and the text of HOST_FAILED tells to run the
 * command in an elevated prompt.
 */
final class PrivilegeNotHeldException extends FileSystemException {

    private static final long serialVersionUID = 1L;

    PrivilegeNotHeldException(Path path, AccessDeniedException cause) {
        super(path.toString(), null, "the privileges to take ownership of a file are not held: run in an elevated prompt");
        initCause(cause);
    }
}
