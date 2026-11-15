package org.pgledger.cli.deploy;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Objects;
import org.pgledger.database.schema.GrantApplier;

/**
 * What the operator decides about the deployment: a value record, validated by Jakarta constraints
 * ({@link DeployInputReader} runs the validator, once, where the values come in). The values are written into unit files,
 * XML and YAML settings, where a space, a quote, a newline, {@code $} (a Spring placeholder), {@code %} (a systemd
 * specifier) or {@code &lt;} would change what the file means, so every value is restricted to the plain characters it
 * needs ({@link DeployForms}) and a value that is not plain is refused ({@code INVALID_DEPLOY_VALUE}, naming the option
 * and never the value: a JDBC URL may hold a secret). The <em>message</em> of every constraint is the name of the option
 * it checks ({@link DeployOptions}), which is all the refusal says; no message template holds the value. Nothing is quoted
 * or escaped to make a value pass.
 *
 * <p>The three targets differ by options and by nothing else: a personal PC and a Windows server are
 * {@code --platform=windows}, a Linux server is {@code --platform=linux}. Every target listens on loopback only; a reverse
 * proxy and its certificate, when the services are to be reached from outside, are the operator's, and the one host name that
 * the proxy passes in {@code Host} is the one optional name of each service.
 *
 * <p>The constraints here are those of every platform. What only a platform can judge (the form of the path of the Java
 * runtime) is its {@link ServicePlatform#refusedOptions}: this record does not know the platforms.
 *
 * @param layout       where the files go (valid: the name of the instance)
 * @param java         absolute path of the {@code java} executable of the Java 25 runtime
 * @param jdbcUrl      JDBC URL of the ledger database; it never holds a password (the passwords are secrets of the secret store)
 * @param appAccount   the application account: the one database account of the MCP server, the admin web and the owner commands
 * @param schema       {@code ledger.schema.name}, or null for the default schema of the account
 * @param mcpPort      loopback port of the MCP server
 * @param adminPort    loopback port of the admin web
 * @param mcpHost      the host name the operator's proxy passes for the MCP server (allowed in its Host check besides the
 *                     loopback names), or null when it is reached on loopback only
 * @param adminHost    the host name the operator's proxy passes for the admin web, or null
 */
public record DeployParameters(
        @Valid DeployLayout layout,
        String java,
        @NotNull(message = DeployOptions.JDBC_URL)
        @Size(max = DeployForms.MAX_URL, message = DeployOptions.JDBC_URL)
        @Pattern(regexp = DeployForms.URL, message = DeployOptions.JDBC_URL) String jdbcUrl,
        String appAccount,
        @Pattern(regexp = DeployForms.SCHEMA, message = DeployOptions.SCHEMA) String schema,
        @Min(value = DeployForms.FIRST_UNPRIVILEGED_PORT, message = DeployOptions.MCP_PORT)
        @Max(value = DeployForms.LAST_PORT, message = DeployOptions.MCP_PORT) int mcpPort,
        @Min(value = DeployForms.FIRST_UNPRIVILEGED_PORT, message = DeployOptions.ADMIN_PORT)
        @Max(value = DeployForms.LAST_PORT, message = DeployOptions.ADMIN_PORT) int adminPort,
        @Size(max = DeployForms.MAX_HOST, message = DeployOptions.MCP_HOST)
        @Pattern(regexp = DeployForms.HOST, message = DeployOptions.MCP_HOST) String mcpHost,
        @Size(max = DeployForms.MAX_HOST, message = DeployOptions.ADMIN_HOST)
        @Pattern(regexp = DeployForms.HOST, message = DeployOptions.ADMIN_HOST) String adminHost) {

    @AssertTrue(message = DeployOptions.APP_ACCOUNT)
    public boolean isAppAccountPlain() {
        return GrantApplier.isAccountName(appAccount);
    }

    /** The two services listen on two ports. */
    @AssertTrue(message = DeployOptions.ADMIN_PORT)
    public boolean isAdminPortApart() {
        return mcpPort != adminPort;
    }

    /** The two services have their own host names: a name is served by one of them. */
    @AssertTrue(message = DeployOptions.ADMIN_HOST)
    public boolean isAdminHostApart() {
        return mcpHost == null || !Objects.equals(mcpHost, adminHost);
    }
}
