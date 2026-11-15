package org.pgledger.migrate1x.secret;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.attribute.UserPrincipal;

/**
 * Boundary to the account this process runs as; the owner of every secret path must be that account, and an owner
 * operation of the tool (the import) is recorded in the audit trail under its name.
 */
public interface ProcessUser {

    UserPrincipal current(FileSystem fileSystem) throws IOException;
}
