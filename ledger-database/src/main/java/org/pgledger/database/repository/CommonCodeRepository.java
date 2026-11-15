package org.pgledger.database.repository;

import java.util.Objects;
import java.util.Optional;
import org.pgledger.domain.model.CodeCatalog;
import org.pgledger.domain.model.CommonCode;
import org.pgledger.domain.rule.CodeDirection;
import org.pgledger.domain.rule.CodeGroup;
import org.pgledger.domain.rule.CodeState;
import org.pgledger.domain.value.ActorId;
import org.pgledger.domain.value.CodeValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Common codes. Entries are inserted and updated (names, descriptions, order, state); there is no delete and the
 * key never changes. The caller bumps {@link CodeRevisionRepository} in the same transaction.
 */
@Repository
public class CommonCodeRepository {

    private static final String COLUMNS = "code_group, code, name_ko, name_en, description_ko, description_en, "
            + "sort_order, state, direction, created_by";

    private final JdbcClient jdbc;

    public CommonCodeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public CodeCatalog catalog(CodeGroup group) {
        return new CodeCatalog(group, jdbc.sql("SELECT " + COLUMNS + " FROM common_code WHERE code_group = :g "
                + "ORDER BY sort_order, code").param("g", group.name())
                .query((rs, n) -> read(rs))
                .list());
    }

    public Optional<CommonCode> find(CodeGroup group, CodeValue code) {
        return catalog(group).entries().stream().filter(c -> Objects.equals(c.code(), code)).findFirst();
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
     * Changes text, order, state and direction of an entry and raises its version, but only when the row still has the version
     * that the caller read ({@code expectedVersion}, from {@link #findVersioned}). False when it does not: another change got
     * there first (or the entry does not exist), and nothing was written. The key and creator stay. Whether a direction may
     * change is the service's rule (see {@code CommonCodeService}).
     */
    public boolean update(CommonCode code, long expectedVersion) {
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
