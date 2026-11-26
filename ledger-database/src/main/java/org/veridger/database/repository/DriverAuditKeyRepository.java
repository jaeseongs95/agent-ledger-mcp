// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The keys of the driver registrations whose audit row was written after the fact ({@code driver_audit_key}). A key can be
 * inserted once: that the database keeps it unique is what makes two runs of {@code migrate} / {@code setup} write one row
 * for one registration. It only joins the transaction of the service method that calls it.
 */
@Repository
public class DriverAuditKeyRepository {

    private final JdbcClient jdbc;

    public DriverAuditKeyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims the registration.
     *
     * @param registrationKey the 64 hex characters that identify the registration
     * @throws org.springframework.dao.DuplicateKeyException when the registration was claimed already
     */
    public void insert(String registrationKey) {
        jdbc.sql("INSERT INTO driver_audit_key (registration_key) VALUES (:key)").param("key", registrationKey).update();
    }
}
