// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.database.support;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.agentledger.database.repository.CriterionRepository;
import org.agentledger.database.repository.IssueRepository;
import org.agentledger.database.repository.ProjectRepository;
import org.agentledger.database.repository.SpecRepository;
import org.agentledger.database.repository.TaskRepository;
import org.agentledger.domain.model.Criterion;
import org.agentledger.domain.model.CriterionFields;
import org.agentledger.domain.model.Issue;
import org.agentledger.domain.model.IssueFields;
import org.agentledger.domain.model.Project;
import org.agentledger.domain.model.Spec;
import org.agentledger.domain.model.SpecFields;
import org.agentledger.domain.model.Task;
import org.agentledger.domain.model.TaskFields;
import org.agentledger.domain.value.AuthorId;
import org.agentledger.domain.value.CriterionId;
import org.agentledger.domain.value.IssueId;
import org.agentledger.domain.value.Patch;
import org.agentledger.domain.value.ProjectCode;
import org.agentledger.domain.value.ProjectId;
import org.agentledger.domain.value.SpecId;
import org.agentledger.domain.value.TaskId;
import org.springframework.context.ApplicationContext;

/** Creates unique projects and rows for a test, so tests of one database do not collide. */
public final class Seed {

    public static final AuthorId ALICE = AuthorId.of("claude:alice");
    public static final AuthorId BOB = AuthorId.of("codex:bob");

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private interface Beans {
        <T> T get(Class<T> type);
    }

    private interface Transactions {
        <T> T run(Supplier<T> work);
    }

    private final Beans beans;
    private final Transactions transactions;

    public Seed(LedgerTestContext ctx) {
        this.beans = ctx::bean;
        this.transactions = ctx::tx;
    }

    public Seed(ApplicationContext context) {
        this.beans = context::getBean;
        this.transactions = Supplier::get;
    }

    /** A project with a unique code; counters start at 0. */
    public Project project() {
        int n = SEQUENCE.incrementAndGet();
        Project project = Project.open(ProjectId.of("p" + n), ProjectCode.of("P" + n), "Project " + n);
        return LedgerTestContext.as(AuthorId.OWNER, () -> transactions.run(() -> beans.get(ProjectRepository.class)
                .insert(project)));
    }

    public Issue issue(Project project, long number, String title, Instant createdAt) {
        IssueFields fields = IssueFields.of(Patch.set(project.id().value()), Patch.set(title), Patch.set("PROCESS"),
                Patch.set("P2"), Patch.set("symptom of " + title), Patch.set("cause of " + title), Patch.absent());
        Issue issue = Issue.open(IssueId.of(project.code(), number), fields, createdAt);
        return LedgerTestContext.as(ALICE, () -> transactions.run(() -> beans.get(IssueRepository.class).save(issue)));
    }

    public Spec spec(Project project, long number, String title, Instant createdAt) {
        return spec(project, SpecId.issue(project.code(), number), title, createdAt);
    }

    public Spec spec(Project project, SpecId id, String title, Instant createdAt) {
        SpecFields fields = SpecFields.of(Patch.set(project.id().value()), Patch.absent(), Patch.set(title),
                Patch.set("summary of " + title), Patch.set("acceptance criteria of " + title), Patch.set("P2"),
                Patch.absent(), Patch.absent(), Patch.absent());
        Spec spec = Spec.create(id, fields, createdAt);
        return LedgerTestContext.as(ALICE, () -> transactions.run(() -> beans.get(SpecRepository.class).save(spec)));
    }

    public Task task(Project project, long number, String title) {
        return task(project, TaskId.issue(project.code(), number), title);
    }

    public Task task(Project project, TaskId id, String title) {
        TaskFields fields = TaskFields.of(Patch.set(project.id().value()), Patch.set(title), Patch.set("purpose"),
                Patch.set("success"), Patch.absent(), Patch.absent(), Patch.absent());
        Task task = Task.plan(id, fields, LedgerTestContext.START);
        return LedgerTestContext.as(ALICE, () -> transactions.run(() -> beans.get(TaskRepository.class).save(task)));
    }

    public Criterion criterion(Project project, String id, String milestone, String code) {
        CriterionFields fields = CriterionFields.of(Patch.set(project.id().value()), Patch.set(milestone),
                Patch.absent(), Patch.set(code), Patch.set("criterion text"));
        Criterion criterion = Criterion.open(CriterionId.of(id), fields);
        return LedgerTestContext.as(ALICE, () -> transactions.run(() -> beans.get(CriterionRepository.class)
                .save(criterion)));
    }
}
