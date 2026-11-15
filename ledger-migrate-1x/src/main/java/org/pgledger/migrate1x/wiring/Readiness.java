package org.pgledger.migrate1x.wiring;

import org.pgledger.database.schema.SchemaProperties;
import org.pgledger.database.schema.SchemaVerifier;
import org.pgledger.database.schema.UtcSessionCheck;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.migrate1x.tool.ErrorCode;
import org.pgledger.migrate1x.tool.FailureException;

/**
 * The startup guard of the server applications, run by the command instead of at startup: the schema of 2.0 must be up to date
 * ({@link SchemaVerifier}, it only looks; {@code ledger-cli migrate} and {@code setup} make it) and the JVM and the database
 * session must run in UTC ({@link UtcSessionCheck}). A failure is the refusal SCHEMA_NOT_READY (the reason names files and
 * versions, never a secret). It exists only when the tool has its database.
 */
public final class Readiness {

    private final SchemaVerifier verifier;
    private final UtcSessionCheck utcCheck;
    private final SchemaProperties properties;
    private boolean verified;

    public Readiness(SchemaVerifier verifier, UtcSessionCheck utcCheck, SchemaProperties properties) {
        this.verifier = verifier;
        this.utcCheck = utcCheck;
        this.properties = properties;
    }

    /** Checks once per process; a failed check is repeated by the next call. */
    public synchronized void require() {
        if (verified) {
            return;
        }
        try {
            verifier.verify();
            if (properties.requireUtc()) {
                utcCheck.verify();
            }
        } catch (InfrastructureException notReady) {
            throw new FailureException(ErrorCode.SCHEMA_NOT_READY, notReady, notReady.getMessage());
        }
        verified = true;
    }
}
