package org.pgledger.admin.support;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.Base64;
import org.springframework.beans.factory.ObjectProvider;
import org.pgledger.application.attachment.AttachmentFlow;
import org.pgledger.application.attachment.UploadCommands;
import org.pgledger.domain.value.Sha256;
import org.pgledger.application.admin.GrantService;
import org.pgledger.application.admin.GrantService.GrantCommand;
import org.pgledger.application.admin.ProjectService;
import org.pgledger.application.admin.ProjectService.ProjectCommand;
import org.pgledger.application.admin.SessionKeyService;
import org.pgledger.application.admin.SessionKeyService.RegisterCommand;
import org.pgledger.application.write.FieldsInput;
import org.pgledger.application.write.RecordCaseCommand;
import org.pgledger.application.write.RecordCaseService;
import org.pgledger.application.write.SaveCommand;
import org.pgledger.application.write.SaveService;
import org.pgledger.application.write.TransitionCommand;
import org.pgledger.application.write.TransitionService;
import org.pgledger.attestation.ArgumentsDigest;
import org.pgledger.attestation.AttestationClaims;
import org.pgledger.attestation.AttestationToken;
import org.pgledger.attestation.PublicKeys;
import org.pgledger.domain.context.ToolScope;
import org.pgledger.domain.value.Patch;
import org.springframework.stereotype.Component;
import org.pgledger.testing.TestOwners;

/**
 * Rows for the page tests, made through the real services in a project of its own (so tests do not collide): the
 * author of the scenario holds the defect and task grants, exactly as an installation does. Every write carries the
 * signed attestation the write gate demands: the author has an Ed25519 key pair whose public key is registered once
 * through {@link SessionKeyService} (as the owner does), and each write is made inside the tool scope of the tool the
 * real call uses, with a token signed for that tool. The real verifier checks it; nothing stands in for it.
 */
@Component
public class Seeds {

    public static final String AUTHOR = "claude:admin-test-author";
    public static final String REASON = "a reason that is long enough";

    /** A project and what was made in it. */
    public record Seeded(String project, String code) {

        public String defectId(int number) {
            return code + "-D-" + "0".repeat(4 - Integer.toString(number).length()) + number;
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
    private final SessionKeyService keys;
    private final ObjectProvider<AttachmentFlow> uploads;
    private final KeyPair pair = newPair();
    private boolean enrolled;

    public Seeds(ProjectService projects, GrantService grants, SaveService saves, TransitionService transitions,
            RecordCaseService cases, MutableClock clock, SessionKeyService keys, ObjectProvider<AttachmentFlow> uploads) {
        this.uploads = uploads;
        this.projects = projects;
        this.grants = grants;
        this.saves = saves;
        this.transitions = transitions;
        this.cases = cases;
        this.clock = clock;
        this.keys = keys;
    }

    /** Moves the clock on by a second, so rows made one after the other have different times. */
    public void tick() {
        clock.advance(java.time.Duration.ofSeconds(1));
    }

    /** A new project of its own where the author may write defects and tasks. */
    public Seeded project() {
        int number = numbers.incrementAndGet();
        Seeded seeded = new Seeded("adm" + number, "ADM" + number);
        projects.add(new ProjectCommand(seeded.project(), seeded.code(), "Project " + number), TestOwners.OWNER);
        enroll();
        grants.grant(new GrantCommand(AUTHOR, seeded.project(), List.of("defect", "task"), null, null), TestOwners.OWNER);
        return seeded;
    }

    public String defect(Seeded seeded, String title) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("project_id", seeded.project());
        fields.put("title", title);
        fields.put("category", "ARC");
        fields.put("severity", "P2");
        fields.put("behavior", "behavior of " + title);
        fields.put("root_cause", "cause of " + title);
        return signedWrite("ledger_save", (attestation, key) -> saves.save(attestation,
                new SaveCommand(key, "defect", null, 0L, fieldsOf(fields), REASON))).id();
    }

    public String task(Seeded seeded, String title) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("project_id", seeded.project());
        fields.put("title", title);
        fields.put("purpose", "purpose of " + title);
        fields.put("success_condition", "success of " + title);
        return signedWrite("ledger_save", (attestation, key) -> saves.save(attestation,
                new SaveCommand(key, "task", null, 0L, fieldsOf(fields), REASON))).id();
    }

    /** A task with an id of its own (a free-text id: any case, with characters such as {@code / # ? :}). */
    public String task(Seeded seeded, String id, String title) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("project_id", seeded.project());
        fields.put("title", title);
        fields.put("purpose", "purpose of " + title);
        fields.put("success_condition", "success of " + title);
        return signedWrite("ledger_save", (attestation, key) -> saves.save(attestation,
                new SaveCommand(key, "task", id, 0L, fieldsOf(fields), REASON))).id();
    }

    public String condition(Seeded seeded, String suffix, String release, String conditionCode) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("project_id", seeded.project());
        fields.put("release", release);
        fields.put("code", conditionCode);
        fields.put("condition_text", "condition " + conditionCode);
        return signedWrite("ledger_save", (attestation, key) -> saves.save(attestation, new SaveCommand(key, "condition",
                seeded.code() + "-C-" + suffix, 0L, fieldsOf(fields), REASON))).id();
    }

    /** The fields of a save from a map of wire keys: a key in the map is a patch set to its value, a key left out is absent. */
    private static FieldsInput fieldsOf(Map<String, String> raw) {
        return new FieldsInput(patch(raw, "project_id"), patch(raw, "title"), patch(raw, "category"),
                patch(raw, "severity"), patch(raw, "behavior"), patch(raw, "root_cause"), patch(raw, "owner_actor_id"),
                patch(raw, "purpose"), patch(raw, "success_condition"), patch(raw, "origin_ref"),
                patch(raw, "origin_quote"), patch(raw, "release"), patch(raw, "goal_label"), patch(raw, "code"),
                patch(raw, "condition_text"));
    }

    private static Patch<String> patch(Map<String, String> raw, String key) {
        return raw.containsKey(key) ? Patch.set(raw.get(key)) : null;
    }

    /** Moves an OPEN defect (version 1) to DISPUTED, with the given evidence text in the history row. */
    public void dispute(String defect, String evidence) {
        signedWrite("ledger_transition", (attestation, key) -> transitions.transition(attestation, new TransitionCommand(key,
                "defect", defect, 1L, "DISPUTED", REASON, evidence, null, null)));
    }

    /** One file in a bundle seed upload. */
    public record BundleFile(String fileName, String mediaType, byte[] body) {
    }

    /**
     * Uploads a single-file bundle through the real five-operation flow and returns the attachment id.
     */
    public String attachment(String fileName, String mediaType, byte[] body) {
        return bundle(List.of(new BundleFile(fileName, mediaType, body)));
    }

    /**
     * Uploads a multi-file bundle through the real five-operation flow ({@code artifact-begin}, chunks per file,
     * {@code artifact-finish}), each call signed for its tool as the hook does, and returns the attachment id.
     */
    public String bundle(List<BundleFile> bundleFiles) {
        AttachmentFlow flow = uploads.getObject();
        List<UploadCommands.BeginFile> declared = new java.util.ArrayList<>(bundleFiles.size());
        for (BundleFile file : bundleFiles) {
            declared.add(new UploadCommands.BeginFile(file.fileName(), file.mediaType(), file.body().length,
                    Sha256.ofBytes(file.body()).hex()));
        }
        String key = "up-" + numbers.incrementAndGet() + "-" + UUID.randomUUID();
        UploadCommands.Begun begun = signed("artifact-begin", token -> flow.begin(token,
                new UploadCommands.Begin(key, declared, null)));
        int chunk = org.pgledger.application.attachment.AttachmentProperties.CHUNK_BYTES;
        for (int seq = 0; seq < bundleFiles.size(); seq++) {
            byte[] body = bundleFiles.get(seq).body();
            String uploadId = begun.files().get(seq).uploadId();
            long sequence = 0;
            for (int from = 0; from < body.length; from += chunk) {
                byte[] part = java.util.Arrays.copyOfRange(body, from, Math.min(body.length, from + chunk));
                String data = Base64.getEncoder().encodeToString(part);
                long number = sequence++;
                signed("artifact-chunk", token -> flow.chunk(token,
                        new UploadCommands.Chunk(uploadId, number, data, Sha256.ofBytes(part).hex())));
            }
        }
        return signed("artifact-finish", token -> flow.finish(token, begun.attachmentId())).attachmentId();
    }

    /**
     * Moves an OPEN defect (version 1) to DISPUTED, the history row pointing to the attachment. DISPUTED needs evidence
     * (DefectStatus): the move carries an evidence URI and digest besides the attachment.
     */
    public void disputeWithAttachment(String defect, String attachmentId) {
        signedWrite("ledger_transition", (attestation, key) -> transitions.transition(attestation, new TransitionCommand(key,
                "defect", defect, 1L, "DISPUTED", REASON, "https://ci.example.org/runs/1", "a".repeat(64), null,
                attachmentId)));
    }

    /** Records an observation of a defect with the given evidence text. */
    public void observe(String defect, String observation, String evidence) {
        signedWrite("ledger_record_case", (attestation, key) -> cases.recordCase(attestation,
                new RecordCaseCommand(key, defect, observation, REASON, evidence, null, null)));
    }

    /**
     * Runs the write as the hook of the author would: in the scope of the tool named {@code tool} and with a token
     * signed for that tool, the current time of the test clock and the empty operation key (the attachment tools carry
     * none in their claim).
     */
    private <R> R signed(String tool, Function<String, R> write) {
        return signed(tool, "", write);
    }

    /**
     * A write tool call: a fresh operation key (the server never makes one up), signed into the claim as the hook does and
     * handed to the write together with the token.
     */
    private <R> R signedWrite(String tool, BiFunction<String, String, R> write) {
        String key = UUID.randomUUID().toString();
        return signed(tool, key, token -> write.apply(token, key));
    }

    private <R> R signed(String tool, String operationKey, Function<String, R> write) {
        enroll();
        // the seeds call the services directly, without the tool binding that digests the raw arguments of a call: they
        // sign and bind the digest of an empty arguments object (token pgla2 covers the arguments digest)
        String digest = ArgumentsDigest.of(Map.of());
        String token = AttestationToken.sign(new AttestationClaims(AUTHOR, tool, operationKey, digest,
                clock.instant().getEpochSecond()), pair.getPrivate()).text();
        return ScopedValue.where(ToolScope.CURRENT, tool).where(ToolScope.ARGUMENTS_DIGEST, digest)
                .call(() -> write.apply(token));
    }

    /** Registers the public key of the author once, as the owner does with the key the hook printed. */
    private synchronized void enroll() {
        if (!enrolled) {
            keys.register(new RegisterCommand(AUTHOR, PublicKeys.encode(pair.getPublic()), null), TestOwners.OWNER);
            enrolled = true;
        }
    }

    private static KeyPair newPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("This JDK has no Ed25519 key pair generator", failure);
        }
    }
}
