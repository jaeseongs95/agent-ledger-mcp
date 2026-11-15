package org.pgledger.database.repository;

import java.util.List;
import java.util.OptionalLong;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.LookupException;
import org.pgledger.domain.value.ProjectCode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Counter based id allocation. The UPDATE takes the row lock and keeps it until the caller's transaction ends, so
 * the SELECT that follows reads the number this transaction issued and a concurrent allocation waits. A rolled
 * back transaction gives its number back. Like every repository it only joins the transaction of the service method that
 * calls it and opens none (principle 4.4, so it carries no {@code @Transactional}); it must be called inside one, because
 * the number is the write of the use case and the lock of its UPDATE lasts until that transaction ends.
 */
@Repository
public class IdAllocator {

    private final JdbcClient jdbc;

    public IdAllocator(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The next defect number of a project (the first is 1). */
    public long nextDefectNo(ProjectCode code) {
        advance("UPDATE project SET next_defect_no = next_defect_no + 1 WHERE code = :code", code);
        return jdbc.sql("SELECT next_defect_no FROM project WHERE code = :code").param("code", code.value())
                .query(Long.class).single();
    }

    /** The next task number of a project (the first is 1). */
    public long nextTaskNo(ProjectCode code) {
        advance("UPDATE project SET next_task_no = next_task_no + 1 WHERE code = :code", code);
        return jdbc.sql("SELECT next_task_no FROM project WHERE code = :code").param("code", code.value())
                .query(Long.class).single();
    }

    /**
     * Takes the number of an explicit defect id. The column holds the last number that was issued, so the next number is the
     * column plus one: that number is taken (the column is set to it, by the one conditional UPDATE that decides it) and a
     * number at or below the column is a past number that nothing issued any more (a free gap), which is accepted and leaves
     * the column alone. A number above the next one is refused, so one explicit id cannot push the counter out of reach of
     * the ids that the server issues.
     *
     * @return empty when the number is accepted; otherwise the next number of the project, which the caller names in its refusal
     * @throws LookupException NOT_FOUND when the project does not exist
     */
    public OptionalLong takeDefectNo(ProjectCode code, long number) {
        return take("UPDATE project SET next_defect_no = :n WHERE code = :code AND next_defect_no = :previous",
                "SELECT next_defect_no FROM project WHERE code = :code", code, number);
    }

    /** As {@link #takeDefectNo} for the number of an explicit task id of the form the server issues. */
    public OptionalLong takeTaskNo(ProjectCode code, long number) {
        return take("UPDATE project SET next_task_no = :n WHERE code = :code AND next_task_no = :previous",
                "SELECT next_task_no FROM project WHERE code = :code", code, number);
    }

    /**
     * The update writes only for the next number (a concurrent transaction that holds the row is waited for, as for any
     * allocation, and the condition is decided against what it committed). When it wrote no row the column is read: it only
     * grows, so a number at or below the value read is past for good, whatever another transaction does meanwhile, and one
     * above it is ahead.
     */
    private OptionalLong take(String update, String select, ProjectCode code, long number) {
        int rows = jdbc.sql(update).param("n", number).param("previous", number - 1).param("code", code.value())
                .update();
        if (rows == 1) {
            return OptionalLong.empty();
        }
        long last = jdbc.sql(select).param("code", code.value()).query(Long.class).optional()
                .orElseThrow(() -> new LookupException(LookupCode.NOT_FOUND, List.of("project", code.value())));
        return number <= last ? OptionalLong.empty() : OptionalLong.of(last + 1);
    }

    /** The next number of a surrogate key sequence (history, fix, case, grant). */
    public long next(Counter counter) {
        int rows = jdbc.sql("UPDATE id_counter SET issued_no = issued_no + 1 WHERE name = :name")
                .param("name", counter.name()).update();
        if (rows != 1) {
            throw new InfrastructureException(InfrastructureCode.INTEGRITY_VIOLATION,
                    List.of("counter row missing: " + counter));
        }
        return jdbc.sql("SELECT issued_no FROM id_counter WHERE name = :name").param("name", counter.name())
                .query(Long.class).single();
    }

    /**
     * After rows with explicit surrogate ids were copied in (an import): the counter is at least {@code atLeast}, so
     * the next number issued is above every id in use. A counter that is already higher is left alone.
     */
    public void raise(Counter counter, long atLeast) {
        int rows = jdbc.sql("UPDATE id_counter SET issued_no = :n WHERE name = :name AND issued_no < :n")
                .param("n", atLeast).param("name", counter.name()).update();
        if (rows > 1) {
            throw new InfrastructureException(InfrastructureCode.INTEGRITY_VIOLATION,
                    List.of("counter row duplicated: " + counter));
        }
    }

    private void advance(String sql, ProjectCode code) {
        if (jdbc.sql(sql).param("code", code.value()).update() != 1) {
            throw new LookupException(LookupCode.NOT_FOUND, List.of("project", code.value()));
        }
    }
}
