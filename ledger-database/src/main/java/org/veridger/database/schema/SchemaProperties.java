// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database.schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Schema settings ({@code ledger.schema.*}). A value that does not bind or validate stops the startup.
 *
 * @param location   resource prefix that holds the {@code V{n}__{name}.sql} files
 * @param requireUtc fail startup when the JVM zone or the database session zone is not UTC
 * @param name       the schema that holds the tables, or absent for the default schema of the account. The
 *                   migrator creates it when it is missing; every connection of the application uses it
 */
@Validated
@ConfigurationProperties(prefix = "ledger.schema")
public record SchemaProperties(
        @DefaultValue(SchemaProperties.DEFAULT_LOCATION) @NotBlank String location,
        @DefaultValue("true") boolean requireUtc,
        @Pattern(regexp = "[a-z][a-z0-9_]{0,29}") String name) {

    /** The default of the binding; code that builds the settings by hand passes it on explicitly. */
    public static final String DEFAULT_LOCATION = "classpath:db/";
}
