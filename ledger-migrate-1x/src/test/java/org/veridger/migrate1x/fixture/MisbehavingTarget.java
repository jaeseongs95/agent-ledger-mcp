// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.migrate1x.fixture;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.veridger.migrate1x.database.LegacyRow;
import org.veridger.migrate1x.database.LegacyTable;
import org.veridger.migrate1x.database.LegacyTargetRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The write side of the legacy import that fails when it is asked to write one table, or that changes one value (MIG-010,
 * MIG-019). A test configuration ({@link Replacement}) that a test adds to its context on purpose; no scan finds it.
 */
public class MisbehavingTarget extends LegacyTargetRepository {

    private final AtomicReference<LegacyTable> failAt = new AtomicReference<>();
    private final AtomicReference<Boolean> tamper = new AtomicReference<>(false);
    private int inserted;

    public MisbehavingTarget(JdbcClient jdbc, DataSource dataSource) {
        super(jdbc, dataSource);
    }

    public void failAt(LegacyTable table) {
        failAt.set(table);
    }

    public void tamper(boolean on) {
        tamper.set(on);
    }

    public int inserted() {
        return inserted;
    }

    @Override
    public void insert(LegacyTable table, LegacyRow row) {
        if (table == failAt.get()) {
            throw new IllegalStateException("injected failure when " + table + " is written");
        }
        inserted++;
        if (tamper.get() && table == LegacyTable.DEFECT && "OLD-D-0003".equals(row.text("id"))) {
            Map<String, Object> changed = new LinkedHashMap<>(row.values());
            changed.put("title", "tampered");
            super.insert(table, new LegacyRow(changed));
        } else {
            super.insert(table, row);
        }
    }

    /** Puts the misbehaving write side in place of the real one. */
    @Configuration(proxyBeanMethods = false)
    public static class Replacement {

        @Bean
        @Primary
        MisbehavingTarget misbehavingTarget(JdbcClient jdbc, DataSource dataSource) {
            return new MisbehavingTarget(jdbc, dataSource);
        }
    }
}
