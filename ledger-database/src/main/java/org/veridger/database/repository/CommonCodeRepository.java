// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database.repository;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.veridger.domain.model.CodeCatalog;
import org.veridger.domain.model.CommonCode;
import org.veridger.domain.rule.CodeDirection;
import org.veridger.domain.rule.CodeGroup;
import org.veridger.domain.rule.CodeState;
import org.veridger.domain.value.ActorId;
import org.veridger.domain.value.CodeValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Common codes. Entries are inserted and updated (names, descriptions, order, state); there is no delete and the
 * key never changes. The caller bumps {@link CodeRevisionRepository} in the same transaction.
 *
 * <p>Read-only / non-transactional catalog reads are cached per repository instance (no {@code ThreadLocal} or mutable
 * static, obeying ARC-018) and validated against {@code code_revision (id = 1)} so changes made by another process
 * (such as the admin web) are observed immediately. Active read-write transactions bypass the cache so uncommitted
 * writes are visible to the transaction and a rollback can never poison the shared snapshot.
 */
@Repository
public class CommonCodeRepository {

    private static final String COLUMNS = "code_group, code, name_ko, name_en, description_ko, description_en, "
            + "sort_order, state, direction, created_by";

    private record Snapshot(long revision, Map<CodeGroup, CodeCatalog> catalogs) {
    }

    private final JdbcClient jdbc;
    private final AtomicReference<Snapshot> cache = new AtomicReference<>();

    public CommonCodeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public CodeCatalog catalog(CodeGroup group) {
        if (inReadWriteTransaction()) {
            return queryCatalog(group);
        }
        Optional<Long> currentRev = jdbc.sql("SELECT revision FROM code_revision WHERE id = 1")
                .query(Long.class).optional();
        if (currentRev.isEmpty()) {
            return queryCatalog(group);
        }
        long rev = currentRev.orElseThrow();
        Snapshot snapshot = cache.get();
        if (snapshot != null && snapshot.revision() == rev) {
            return snapshot.catalogs().get(group);
        }
        Snapshot refreshed = loadAll(rev);
        Optional<Long> revAfter = jdbc.sql("SELECT revision FROM code_revision WHERE id = 1")
                .query(Long.class).optional();
        if (revAfter.isPresent() && revAfter.orElseThrow() == rev) {
            cache.accumulateAndGet(refreshed,
                    (prev, next) -> prev != null && prev.revision() > next.revision() ? prev : next);
        }
        return refreshed.catalogs().get(group);
    }

    public Optional<CommonCode> find(CodeGroup group, CodeValue code) {
        if (inReadWriteTransaction()) {
            return jdbc.sql("SELECT " + COLUMNS + " FROM common_code WHERE code_group = :g AND code = :c")
                    .param("g", group.name()).param("c", code.value())
                    .query((rs, n) -> read(rs))
                    .optional();
        }
        return catalog(group).entries().stream().filter(c -> Objects.equals(c.code(), code)).findFirst();
    }

    private CodeCatalog queryCatalog(CodeGroup group) {
        return new CodeCatalog(group, jdbc.sql("SELECT " + COLUMNS + " FROM common_code WHERE code_group = :g "
                + "ORDER BY sort_order, code").param("g", group.name())
                .query((rs, n) -> read(rs))
                .list());
    }

    private Snapshot loadAll(long revision) {
        Map<CodeGroup, List<CommonCode>> grouped = new EnumMap<>(CodeGroup.class);
        for (CodeGroup g : CodeGroup.values()) {
            grouped.put(g, new ArrayList<>());
        }
        List<CommonCode> all = jdbc.sql("SELECT " + COLUMNS + " FROM common_code "
                + "ORDER BY code_group, sort_order, code").query((rs, n) -> read(rs)).list();
        for (CommonCode code : all) {
            grouped.get(code.group()).add(code);
        }
        Map<CodeGroup, CodeCatalog> catalogs = new EnumMap<>(CodeGroup.class);
        for (Map.Entry<CodeGroup, List<CommonCode>> entry : grouped.entrySet()) {
            catalogs.put(entry.getKey(), new CodeCatalog(entry.getKey(), entry.getValue()));
        }
        return new Snapshot(revision, Map.copyOf(catalogs));
    }

    private static boolean inReadWriteTransaction() {
        return TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly();
    }

    /** The code with the version of its row, for a change that must not overwrite another one ({@link #update}). */
    public Optional<VersionedCode> findVersioned(CodeGroup group, CodeValue code) {
        return jdbc.sql("SELECT " + COLUMNS + ", version FROM common_code WHERE code_group = :g AND code = :c")
                .param("g", group.name()).param("c", code.value())
                .query((rs, n) -> new VersionedCode(read(rs), rs.getLong("version")))
                .list().stream().findFirst();
    }

    private static CommonCode read(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CommonCode(CodeGroup.valueOf(rs.getString("code_group")),
                CodeValue.of(rs.getString("code")), rs.getString("name_ko"), rs.getString("name_en"),
                rs.getString("description_ko"), rs.getString("description_en"), rs.getInt("sort_order"),
                CodeState.valueOf(rs.getString("state")), CodeDirection.valueOf(rs.getString("direction")),
                ActorId.ofStored(rs.getString("created_by")));
    }

    public void insert(CommonCode code) {
        cache.set(null);
        jdbc.sql("INSERT INTO common_code (" + COLUMNS + ") VALUES (:group, :code, :nameKo, :nameEn, :descKo, "
                + ":descEn, :sortOrder, :state, :direction, :createdBy)")
                .param("group", code.group().name())
                .param("code", code.code().value())
                .param("nameKo", code.nameKo())
                .param("nameEn", code.nameEn())
                .param("descKo", code.descriptionKo())
                .param("descEn", code.descriptionEn())
                .param("sortOrder", code.sortOrder())
                .param("state", code.state().name())
                .param("direction", code.direction().name())
                .param("createdBy", code.createdBy().value())
                .update();
    }

    /**
     * The write of a relation holds the entry of its kind: raises the version of the entry, but only while it still has the
     * direction that the caller read. The row is locked until the transaction ends, so a change of the direction (the
     * conditional {@link #update}, which counts the stored relations first) waits for the relation to be committed, and a
     * relation is never stored under the direction that the entry had before a change that was committed first. False when the
     * entry has another direction now (or does not exist): nothing was written, and the caller sends its request again.
     */
    public boolean holdDirection(CodeGroup group, CodeValue code, CodeDirection direction) {
        return jdbc.sql("UPDATE common_code SET version = version + 1 WHERE code_group = :group AND code = :code "
                + "AND direction = :direction")
                .param("group", group.name())
                .param("code", code.value())
                .param("direction", direction.name())
                .update() == 1;
    }

    /**
     * Changes text, order, state and direction of an entry and raises its version, but only when the row still has the version
     * that the caller read ({@code expectedVersion}, from {@link #findVersioned}). False when it does not: another change got
     * there first (or the entry does not exist), and nothing was written. The key and creator stay. Whether a direction may
     * change is the service's rule (see {@code CommonCodeService}).
     */
    public boolean update(CommonCode code, long expectedVersion) {
        cache.set(null);
        return jdbc.sql("UPDATE common_code SET name_ko = :nameKo, name_en = :nameEn, description_ko = :descKo, "
                + "description_en = :descEn, sort_order = :sortOrder, state = :state, direction = :direction, "
                + "version = version + 1 WHERE code_group = :group AND code = :code AND version = :expected")
                .param("expected", expectedVersion)
                .param("nameKo", code.nameKo())
                .param("nameEn", code.nameEn())
                .param("descKo", code.descriptionKo())
                .param("descEn", code.descriptionEn())
                .param("sortOrder", code.sortOrder())
                .param("state", code.state().name())
                .param("direction", code.direction().name())
                .param("group", code.group().name())
                .param("code", code.code().value())
                .update() == 1;
    }
}
