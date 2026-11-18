package org.pgledger.application.write;

/** Links (or unlinks) two entities; the kind is issue_task, issue_spec, spec_task, criterion_task, criterion_issue, criterion_spec or a relation code. */
public record LinkCommand(String operationKey, String kind, String a, String b, String reason, Boolean remove)
        implements WriteCommand {
}
