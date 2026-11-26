// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli.secret;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.attribute.UserPrincipal;

import org.veridger.cli.ErrorCode;
import org.veridger.cli.FailureException;
import org.veridger.files.ProcessAccount;
import org.springframework.stereotype.Component;

/** The account of this process (JDK ProcessHandle) resolved to a principal of the given file system, so it compares equal to file owners. */
@Component
public class SystemProcessUser implements ProcessUser {

    @Override
    public UserPrincipal current(FileSystem fileSystem) throws IOException {
        if (ProcessAccount.name().isEmpty()) {
            throw new FailureException(ErrorCode.UNKNOWN_PROCESS_USER);
        }
        return ProcessAccount.principal(fileSystem);
    }
}
