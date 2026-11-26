// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.mcp.support;

import java.util.List;
import org.agentledger.application.admin.GrantService;
import org.agentledger.application.admin.GrantService.GrantCommand;
import org.agentledger.application.admin.ProjectService;
import org.agentledger.application.admin.ProjectService.ProjectCommand;
import org.agentledger.testing.TestOwners;
import org.springframework.context.ApplicationContext;

/**
 * Isolated project fixture with standard test actors and 2.0 scopes ({@code issue}, {@code spec}, {@code task},
 * {@code deliverable}, {@code verify}).
 */
public final class Scenario {

    public static final String AUTHOR = "claude:e2e-author";
    public static final String REVIEWER = "codex:e2e-reviewer";
    public static final String ISSUE_ONLY = "codex:issue-only";
    public static final String NOGRANT = "gemini:e2e-nogrant";
    public static final String REASON = "a reason that is long enough";
    public static final String EVIDENCE = "https://evidence.example/1";

    private final ApplicationContext context;
    private final String projectId;
    private final String code;

    public Scenario(ApplicationContext context) {
        this.context = context;
        int number = context.getBean(ProjectNumbers.class).next();
        this.projectId = "p" + number;
        this.code = "E2E" + number;
        context.getBean(ProjectService.class).add(new ProjectCommand(projectId, code, "Project " + number),
                TestOwners.OWNER);
        grant(AUTHOR, "issue", "spec", "task", "deliverable", "verify");
        grant(REVIEWER, "deliverable", "verify");
        grant(ISSUE_ONLY, "issue");
    }

    public void grant(String author, String... scopes) {
        context.getBean(GrantService.class).grant(new GrantCommand(author, projectId, List.of(scopes), null, null),
                TestOwners.OWNER);
    }

    public String actorWith(String label, String... scopes) {
        String actor = "claude:" + label + "-" + projectId;
        grant(actor, scopes);
        return actor;
    }

    public <T> T bean(Class<T> type) {
        return context.getBean(type);
    }

    public String projectId() {
        return projectId;
    }

    public String code() {
        return code;
    }

    public String issueId(int number) {
        return code + "-I-" + String.format("%04d", number);
    }

    public String specId(int number) {
        return code + "-S-" + String.format("%04d", number);
    }

    public String taskId(int number) {
        return code + "-T-" + String.format("%04d", number);
    }

    public String criterionId(String suffix) {
        return code + "-C-" + suffix;
    }
}
