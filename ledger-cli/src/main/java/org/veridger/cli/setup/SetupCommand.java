// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli.setup;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.veridger.application.admin.CommonCodeService.CodeCommand;
import org.veridger.cli.CliCommand;
import org.veridger.cli.CliMessages;
import org.veridger.cli.CliOutput;
import org.veridger.cli.Command;
import org.veridger.cli.ErrorCode;
import org.veridger.cli.OperatorStepException;
import org.veridger.cli.UsageException;
import org.veridger.cli.database.CliLedger;
import org.veridger.cli.database.DatabaseBeans;
import org.veridger.cli.schema.SchemaMigration;
import org.veridger.cli.seed.PackagedSeed;
import org.veridger.database.schema.ApplicationAccountAccess;
import org.veridger.domain.model.CommonCode;
import org.veridger.domain.rule.CodeGroup;
import org.veridger.domain.value.Tokens;
import org.veridger.seed.SeedCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * {@code setup [--owner-account=<account>] [--app-account=<account>] [--stdin]}: the first setup of the ledger database, and
 * what an operator runs after every upgrade as well. In this order:
 *
 * <ol>
 * <li>the migration of the schema, the path of {@code migrate} with its three options ({@link SchemaMigration}: the schema
 * files and the history, the grants of the application account);</li>
 * <li>the check that the application account can use the schema ({@link ApplicationAccountAccess}). The privilege that lets an
 * account use a schema is a statement of the database system, not ANSI SQL, so it is not in the product SQL: when it is
 * missing the command says in a generic sentence that an operator has to give it, and ends with exit code 3
 * ({@link OperatorStepException}) before anything is added;</li>
 * <li>the codes of the seed bundle ({@link PackagedSeed}) that the ledger does not have, added through the service of
 * {@code code-set} and of the admin web, so the code revision and the owner's audit trail ({@code cli:<process user>}) are kept;
 * a code that exists is never changed (a code the owner changed or retired stays as he left it). All of them are one call of
 * the service, so one transaction: a seed that fails half way adds nothing;</li>
 * <li>the comparison of the seed with the database by counts and by digest ({@link SeedReport});</li>
 * <li>the first key of the attachments, when the attachments are configured and no key is active yet
 * ({@link AttachmentKeySetup}: the key file, and its id as {@code ledger.attachments.active-key-id} in {@code local.yaml}).</li>
 * </ol>
 *
 * Every step can be repeated: a run that stopped at the grants or at the check (or whose seed failed, which leaves no code
 * behind) is finished by the next one, and a run on a complete installation changes nothing and prints the same report.
 */
@Component
public class SetupCommand implements Command {

    private final SchemaMigration migration;
    private final PackagedSeed seed;
    private final ObjectProvider<ApplicationAccountAccess> applicationAccount;
    private final ObjectProvider<CliLedger> ledger;
    private final AttachmentKeySetup attachmentKey;
    private final CliOutput output;
    private final CliMessages messages;

    SetupCommand(SchemaMigration migration, PackagedSeed seed, ObjectProvider<ApplicationAccountAccess> applicationAccount,
            ObjectProvider<CliLedger> ledger, AttachmentKeySetup attachmentKey, CliOutput output, CliMessages messages) {
        this.migration = migration;
        this.seed = seed;
        this.applicationAccount = applicationAccount;
        this.ledger = ledger;
        this.attachmentKey = attachmentKey;
        this.output = output;
        this.messages = messages;
    }

    @Override
    public CliCommand name() {
        return CliCommand.SETUP;
    }

    @Override
    public Set<String> options() {
        return SchemaMigration.OPTIONS;
    }

    @Override
    public void run(List<String> operands, ApplicationArguments arguments) {
        if (!operands.isEmpty()) {
            throw new UsageException(ErrorCode.TOO_MANY_ARGUMENTS);
        }
        Optional<String> appAccount = migration.appAccount(arguments);
        // a seed that cannot be read is found before the database is touched
        List<SeedCode> wanted = seed.codes();
        migration.migrate();
        appAccount.ifPresent(migration::grant);
        requireApplicationAccount();
        CliLedger target = DatabaseBeans.require(ledger);
        List<CodeCommand> commands = wanted.stream().map(code -> new CodeCommand(code.group(), code.code(), code.nameKo(),
                code.nameEn(), code.descriptionKo(), code.descriptionEn(), code.sortOrder(), code.state())).toList();
        // one use case, one service method, one transaction: a seed that fails half way leaves no part of it behind
        int added = target.call(() -> target.codes().seed(commands, target.actor()));
        output.info(messages.get("setup.seeded", String.valueOf(added)));
        SeedReport report = SeedReport.compare(wanted, current(target, wanted));
        output.info(messages.get("setup.report", String.valueOf(report.seed()), String.valueOf(report.present()),
                String.valueOf(report.same()), String.valueOf(report.changed()), report.seedDigest(),
                report.databaseDigest()));
        attachmentKey.ensure(target);
    }

    /** The check of the second step; the schema is migrated and nothing else has been done when it stops here. */
    private void requireApplicationAccount() {
        ApplicationAccountAccess access = DatabaseBeans.require(applicationAccount);
        if (!access.canUseSchema()) {
            throw new OperatorStepException(ErrorCode.APP_ACCOUNT_NO_ACCESS, access.account());
        }
    }

    /** The codes of the database in the groups the seed names. */
    private static List<SeedCode> current(CliLedger target, List<SeedCode> wanted) {
        List<SeedCode> database = new ArrayList<>();
        for (String name : wanted.stream().map(SeedCode::group).distinct().toList()) {
            CodeGroup group = target.call(() -> Tokens.parse("group", name, CodeGroup.values()));
            for (CommonCode code : target.call(() -> target.codes().list(group))) {
                database.add(SeedReport.of(code));
            }
        }
        return database;
    }
}
