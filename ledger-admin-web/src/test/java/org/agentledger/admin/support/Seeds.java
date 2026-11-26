// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.admin.support;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.agentledger.application.admin.GrantService;
import org.agentledger.application.admin.GrantService.GrantCommand;
import org.agentledger.application.admin.ProjectService;
import org.agentledger.application.admin.ProjectService.ProjectCommand;
import org.agentledger.application.attachment.AttachmentFlow;
import org.agentledger.application.attachment.UploadCommands;
import org.agentledger.application.write.FieldsInput;
import org.agentledger.application.write.RecordCaseCommand;
import org.agentledger.application.write.RecordCaseService;
import org.agentledger.application.write.SaveCommand;
import org.agentledger.application.write.SaveService;
import org.agentledger.application.write.TransitionCommand;
import org.agentledger.application.write.TransitionService;
import org.agentledger.domain.value.Patch;
import org.agentledger.domain.value.Sha256;
import org.agentledger.testing.TestOwners;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Rows for the page tests, made through the real services in a project of its own (so tests do not collide): the
 * author of the scenario holds the issue, spec, task, and deliverable grants, exactly as an installation does.
 */
@Component
public class Seeds {

    public static final String AUTHOR = "claude:admin-test-author";
    public static final String REASON = "a reason that is long enough";

    /** A project and what was made in it. */
    public record Seeded(String project, String code) {

        public String issueId(int number) {
            return code + "-I-" + "0".repeat(4 - Integer.toString(number).length()) + number;
        }

        public String specId(int number) {
            return code + "-S-" + "0".repeat(4 - Integer.toString(number).length()) + number;
        }

        public String taskId(int number) {
            return code + "-T-" + "0".repeat(4 - Integer.toString(number).length()) + number;
        }
    }

    private final AtomicInteger numbers = new AtomicInteger();
    private final ProjectService projects;
    private final GrantService grants;
    private final SaveService saves;
    private final TransitionService transitions;
    private final RecordCaseService cases;
    private final MutableClock clock;
    private final ObjectProvider<AttachmentFlow> uploads;

    public Seeds(ProjectService projects, GrantService grants, SaveService saves, TransitionService transitions,
            RecordCaseService cases, MutableClock clock, ObjectProvider<AttachmentFlow> uploads) {
        this.uploads = uploads;
        this.projects = projects;
        this.grants = grants;
        this.saves = saves;
        this.transitions = transitions;
        this.cases = cases;
        this.clock = clock;
    }

    /** Moves the clock on by a second, so rows made one after the other have different times. */
    public void tick() {
        clock.advance(java.time.Duration.ofSeconds(1));
    }

    /** A new project of its own where the author may write issues, specifications, tasks, and deliverables. */
    public Seeded project() {
        int number = numbers.incrementAndGet();
        Seeded seeded = new Seeded("adm" + number, "ADM" + number);
        projects.add(new ProjectCommand(seeded.project(), seeded.code(), "Project " + number), TestOwners.OWNER);
        grants.grant(new GrantCommand(AUTHOR, seeded.project(), List.of("issue", "spec", "task", "deliverable"),
                null, null), TestOwners.OWNER);
        return seeded;
    }

    public String issue(Seeded seeded, String title) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("project_id", seeded.project());
        fields.put("title", title);
        fields.put("category", "ARC");
        fields.put("severity", "P2");
        fields.put("symptom", "symptom of " + title);
        fields.put("root_cause", "cause of " + title);
        return saves.save(AUTHOR, new SaveCommand(UUID.randomUUID().toString(), "issue", null, 0L,
                fieldsOf(fields), REASON)).id();
    }

    public String spec(Seeded seeded, String title) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("project_id", seeded.project());
        fields.put("title", title);
        fields.put("summary", "summary of " + title);
        fields.put("acceptance_criteria", "acceptance of " + title);
        fields.put("priority", "P1");
        return saves.save(AUTHOR, new SaveCommand(UUID.randomUUID().toString(), "spec", null, 0L,
                fieldsOf(fields), REASON)).id();
    }

    public String spec(Seeded seeded, String id, String title) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("project_id", seeded.project());
        fields.put("title", title);
        fields.put("summary", "summary of " + title);
        fields.put("acceptance_criteria", "acceptance of " + title);
        return saves.save(AUTHOR, new SaveCommand(UUID.randomUUID().toString(), "spec", id, 0L,
                fieldsOf(fields), REASON)).id();
    }

    public String task(Seeded seeded, String title) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("project_id", seeded.project());
        fields.put("title", title);
        fields.put("purpose", "purpose of " + title);
        fields.put("success_condition", "success of " + title);
        return saves.save(AUTHOR, new SaveCommand(UUID.randomUUID().toString(), "task", null, 0L,
                fieldsOf(fields), REASON)).id();
    }

    /** A task with an id of its own (a free-text id: any case, with characters such as {@code / # ? :}). */
    public String task(Seeded seeded, String id, String title) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("project_id", seeded.project());
        fields.put("title", title);
        fields.put("purpose", "purpose of " + title);
        fields.put("success_condition", "success of " + title);
        return saves.save(AUTHOR, new SaveCommand(UUID.randomUUID().toString(), "task", id, 0L,
                fieldsOf(fields), REASON)).id();
    }

    public String criterion(Seeded seeded, String suffix, String milestone, String criterionCode) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("project_id", seeded.project());
        fields.put("milestone_name", milestone);
        fields.put("code", criterionCode);
        fields.put("criterion_text", "criterion " + criterionCode);
        return saves.save(AUTHOR, new SaveCommand(UUID.randomUUID().toString(), "criterion",
                seeded.code() + "-C-" + suffix, 0L, fieldsOf(fields), REASON)).id();
    }

    private static FieldsInput fieldsOf(Map<String, String> raw) {
        return new FieldsInput(
                patch(raw, "project_id"),
                patch(raw, "parent_id"),
                patch(raw, "title"),
                patch(raw, "category"),
                patch(raw, "severity"),
                patch(raw, "symptom"),
                patch(raw, "root_cause"),
                patch(raw, "summary"),
                patch(raw, "acceptance_criteria"),
                patch(raw, "priority"),
                patch(raw, "owner_actor_id"),
                patch(raw, "purpose"),
                patch(raw, "success_condition"),
                patch(raw, "origin_ref"),
                patch(raw, "origin_quote"),
                patch(raw, "milestone_name"),
                patch(raw, "goal_label"),
                patch(raw, "code"),
                patch(raw, "criterion_text"));
    }

    private static Patch<String> patch(Map<String, String> raw, String key) {
        return raw.containsKey(key) ? Patch.set(raw.get(key)) : null;
    }

    /** Moves an OPEN issue (version 1) to DISPUTED, with the given evidence text in the history row. */
    public void dispute(String issue, String evidence) {
        transitions.transition(AUTHOR, new TransitionCommand(UUID.randomUUID().toString(),
                "issue", issue, 1L, "DISPUTED", REASON, evidence, null, null));
    }

    /** One file in a bundle seed upload. */
    public record BundleFile(String fileName, String mediaType, byte[] body) {
    }

    /** Uploads a single-file bundle through the real five-operation flow and returns the attachment id. */
    public String attachment(String fileName, String mediaType, byte[] body) {
        return bundle(List.of(new BundleFile(fileName, mediaType, body)));
    }

    /** Uploads a multi-file bundle through the real five-operation flow and returns the attachment id. */
    public String bundle(List<BundleFile> bundleFiles) {
        AttachmentFlow flow = uploads.getObject();
        List<UploadCommands.BeginFile> declared = new java.util.ArrayList<>(bundleFiles.size());
        for (BundleFile file : bundleFiles) {
            declared.add(new UploadCommands.BeginFile(file.fileName(), file.mediaType(), file.body().length,
                    Sha256.ofBytes(file.body()).hex()));
        }
        String key = "up-" + numbers.incrementAndGet() + "-" + UUID.randomUUID();
        UploadCommands.Begun begun = flow.begin(AUTHOR, new UploadCommands.Begin(key, declared, null));
        int chunk = org.agentledger.application.attachment.AttachmentProperties.CHUNK_BYTES;
        for (int seq = 0; seq < bundleFiles.size(); seq++) {
            byte[] body = bundleFiles.get(seq).body();
            String uploadId = begun.files().get(seq).uploadId();
            long sequence = 0;
            for (int from = 0; from < body.length; from += chunk) {
                byte[] part = java.util.Arrays.copyOfRange(body, from, Math.min(body.length, from + chunk));
                String data = Base64.getEncoder().encodeToString(part);
                long number = sequence++;
                flow.chunk(AUTHOR, new UploadCommands.Chunk(uploadId, number, data, Sha256.ofBytes(part).hex()));
            }
        }
        return flow.finish(AUTHOR, begun.attachmentId()).attachmentId();
    }

    /** Moves an OPEN issue (version 1) to DISPUTED, the history row pointing to the attachment. */
    public void disputeWithAttachment(String issue, String attachmentId) {
        transitions.transition(AUTHOR, new TransitionCommand(UUID.randomUUID().toString(),
                "issue", issue, 1L, "DISPUTED", REASON, "https://ci.example.org/runs/1", "a".repeat(64), null,
                attachmentId));
    }

    /** Records an observation of an issue with the given evidence text. */
    public void observe(String issue, String observation, String evidence) {
        cases.recordCase(AUTHOR, new RecordCaseCommand(UUID.randomUUID().toString(), issue, observation,
                REASON, evidence, null, null));
    }
}
