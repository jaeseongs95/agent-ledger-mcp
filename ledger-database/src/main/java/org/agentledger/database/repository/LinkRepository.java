// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.database.repository;

import java.util.List;
import org.agentledger.domain.model.CriterionLink;
import org.agentledger.domain.model.IssueRelation;
import org.agentledger.domain.model.IssueSpecLink;
import org.agentledger.domain.model.IssueTaskLink;
import org.agentledger.domain.model.SpecTaskLink;
import org.agentledger.domain.value.ActorId;
import org.agentledger.domain.value.CodeValue;
import org.agentledger.domain.value.CriterionId;
import org.agentledger.domain.value.IssueId;
import org.agentledger.domain.value.SpecId;
import org.agentledger.domain.value.TaskId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The five link tables ({@code issue_task}, {@code issue_spec}, {@code spec_task}, {@code issue_relation},
 * {@code criterion_link}). Links are the only rows deleted (UNLINK; history retains {@code before_data}).
 */
@Repository
public class LinkRepository {

    private final JdbcClient jdbc;

    public LinkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean add(IssueTaskLink link) {
        return jdbc.sql("INSERT INTO issue_task (issue_id, task_id, created_by) "
                + "SELECT i.id, :task, :by FROM issue i WHERE i.id = :issue "
                + "AND NOT EXISTS (SELECT 1 FROM issue_task x WHERE x.issue_id = :issue AND x.task_id = :task)")
                .param("task", link.taskId().value())
                .param("by", link.createdBy().value())
                .param("issue", link.issueId().value())
                .update() == 1;
    }

    public boolean remove(IssueId issue, TaskId task) {
        return jdbc.sql("DELETE FROM issue_task WHERE issue_id = :issue AND task_id = :task")
                .param("issue", issue.value()).param("task", task.value()).update() == 1;
    }

    public List<IssueTaskLink> tasksOf(IssueId issue) {
        return jdbc.sql("SELECT issue_id, task_id, created_by FROM issue_task WHERE issue_id = :issue "
                + "ORDER BY task_id").param("issue", issue.value())
                .query((rs, n) -> new IssueTaskLink(IssueId.of(rs.getString("issue_id")),
                        TaskId.of(rs.getString("task_id")), ActorId.ofStored(rs.getString("created_by"))))
                .list();
    }

    public boolean add(IssueSpecLink link) {
        return jdbc.sql("INSERT INTO issue_spec (issue_id, spec_id, created_by) "
                + "SELECT i.id, :spec, :by FROM issue i WHERE i.id = :issue "
                + "AND NOT EXISTS (SELECT 1 FROM issue_spec x WHERE x.issue_id = :issue AND x.spec_id = :spec)")
                .param("spec", link.specId().value())
                .param("by", link.createdBy().value())
                .param("issue", link.issueId().value())
                .update() == 1;
    }

    public boolean remove(IssueId issue, SpecId spec) {
        return jdbc.sql("DELETE FROM issue_spec WHERE issue_id = :issue AND spec_id = :spec")
                .param("issue", issue.value()).param("spec", spec.value()).update() == 1;
    }

    public boolean add(SpecTaskLink link) {
        return jdbc.sql("INSERT INTO spec_task (spec_id, task_id, created_by) "
                + "SELECT s.id, :task, :by FROM spec s WHERE s.id = :spec "
                + "AND NOT EXISTS (SELECT 1 FROM spec_task x WHERE x.spec_id = :spec AND x.task_id = :task)")
                .param("task", link.taskId().value())
                .param("by", link.createdBy().value())
                .param("spec", link.specId().value())
                .update() == 1;
    }

    public boolean remove(SpecId spec, TaskId task) {
        return jdbc.sql("DELETE FROM spec_task WHERE spec_id = :spec AND task_id = :task")
                .param("spec", spec.value()).param("task", task.value()).update() == 1;
    }

    public boolean add(IssueRelation relation) {
        return jdbc.sql("INSERT INTO issue_relation (a, b, kind, created_by) "
                + "SELECT i.id, :b, :kind, :by FROM issue i WHERE i.id = :a "
                + "AND NOT EXISTS (SELECT 1 FROM issue_relation x WHERE x.a = :a AND x.b = :b AND x.kind = :kind)")
                .param("b", relation.b().value())
                .param("kind", relation.relation().value())
                .param("by", relation.createdBy().value())
                .param("a", relation.a().value())
                .update() == 1;
    }

    public boolean remove(IssueRelation relation) {
        return jdbc.sql("DELETE FROM issue_relation WHERE a = :a AND b = :b AND kind = :kind")
                .param("a", relation.a().value()).param("b", relation.b().value())
                .param("kind", relation.relation().value()).update() == 1;
    }

    /** How many stored relations have this kind: the direction of a kind cannot change while there are some. */
    public long countRelations(CodeValue kind) {
        return jdbc.sql("SELECT COUNT(*) FROM issue_relation WHERE kind = :kind").param("kind", kind.value())
                .query(Long.class).single();
    }

    public List<IssueRelation> relationsOf(IssueId issue) {
        return jdbc.sql("SELECT a, b, kind, created_by FROM issue_relation WHERE a = :i OR b = :i "
                + "ORDER BY a, b, kind").param("i", issue.value())
                .query((rs, n) -> new IssueRelation(IssueId.of(rs.getString("a")), IssueId.of(rs.getString("b")),
                        CodeValue.of(rs.getString("kind")), ActorId.ofStored(rs.getString("created_by"))))
                .list();
    }

    public boolean add(CriterionLink link) {
        String col;
        String target;
        if (link.taskId() != null) {
            col = "task_id";
            target = link.taskId().value();
        } else if (link.issueId() != null) {
            col = "issue_id";
            target = link.issueId().value();
        } else {
            col = "spec_id";
            target = link.specId().value();
        }
        String sql = "INSERT INTO criterion_link (link_key, criterion_id, " + col + ", created_by) "
                + "SELECT :key, c.id, :target, :by FROM milestone_criterion c WHERE c.id = :criterion "
                + "AND NOT EXISTS (SELECT 1 FROM criterion_link x WHERE x.link_key = :key)";
        return jdbc.sql(sql)
                .param("key", link.linkKey())
                .param("target", target)
                .param("by", link.createdBy().value())
                .param("criterion", link.criterionId().value())
                .update() == 1;
    }

    public boolean remove(CriterionLink link) {
        return jdbc.sql("DELETE FROM criterion_link WHERE link_key = :key").param("key", link.linkKey())
                .update() == 1;
    }

    /** Ids of the criteria linked to an issue, in id order. */
    public List<String> criteriaOfIssue(IssueId issue) {
        return jdbc.sql("SELECT criterion_id FROM criterion_link WHERE issue_id = :i ORDER BY criterion_id")
                .param("i", issue.value()).query(String.class).list();
    }

    /** Ids of the criteria linked to a spec, in id order. */
    public List<String> criteriaOfSpec(SpecId spec) {
        return jdbc.sql("SELECT criterion_id FROM criterion_link WHERE spec_id = :s ORDER BY criterion_id")
                .param("s", spec.value()).query(String.class).list();
    }

    /** Ids of the criteria linked to a task, in id order. */
    public List<String> criteriaOfTask(TaskId task) {
        return jdbc.sql("SELECT criterion_id FROM criterion_link WHERE task_id = :t ORDER BY criterion_id")
                .param("t", task.value()).query(String.class).list();
    }

    /** Ids of the issues a task works on, in id order. */
    public List<String> issuesOfTask(TaskId task) {
        return jdbc.sql("SELECT issue_id FROM issue_task WHERE task_id = :t ORDER BY issue_id")
                .param("t", task.value()).query(String.class).list();
    }

    /** Ids of the specs linked to an issue, in id order. */
    public List<String> specsOfIssue(IssueId issue) {
        return jdbc.sql("SELECT spec_id FROM issue_spec WHERE issue_id = :i ORDER BY spec_id")
                .param("i", issue.value()).query(String.class).list();
    }

    /** Ids of the issues linked to a spec, in id order. */
    public List<String> issuesOfSpec(SpecId spec) {
        return jdbc.sql("SELECT issue_id FROM issue_spec WHERE spec_id = :s ORDER BY issue_id")
                .param("s", spec.value()).query(String.class).list();
    }

    /** Ids of the specs linked to a task, in id order. */
    public List<String> specsOfTask(TaskId task) {
        return jdbc.sql("SELECT spec_id FROM spec_task WHERE task_id = :t ORDER BY spec_id")
                .param("t", task.value()).query(String.class).list();
    }

    /** Ids of the tasks linked to a spec, in id order. */
    public List<String> tasksOfSpec(SpecId spec) {
        return jdbc.sql("SELECT task_id FROM spec_task WHERE spec_id = :s ORDER BY task_id")
                .param("s", spec.value()).query(String.class).list();
    }

    public List<CriterionLink> linksOf(CriterionId criterion) {
        return jdbc.sql("SELECT criterion_id, task_id, issue_id, spec_id, created_by FROM criterion_link "
                + "WHERE criterion_id = :c ORDER BY link_key").param("c", criterion.value())
                .query((rs, n) -> {
                    CriterionId crit = CriterionId.of(rs.getString("criterion_id"));
                    ActorId by = ActorId.ofStored(rs.getString("created_by"));
                    String task = rs.getString("task_id");
                    if (task != null) {
                        return CriterionLink.toTask(crit, TaskId.of(task), by);
                    }
                    String issue = rs.getString("issue_id");
                    if (issue != null) {
                        return CriterionLink.toIssue(crit, IssueId.of(issue), by);
                    }
                    return CriterionLink.toSpec(crit, SpecId.of(rs.getString("spec_id")), by);
                }).list();
    }
}
