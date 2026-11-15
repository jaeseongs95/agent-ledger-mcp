package org.pgledger.application.write;

import org.pgledger.database.repository.CommonCodeRepository;
import org.pgledger.domain.model.Defect;
import org.pgledger.domain.rule.CodeGroup;
import org.pgledger.domain.value.CodeValue;
import org.springframework.stereotype.Component;

/** The common-code rules of the defect columns, checked in Java against the codes read in the same transaction. */
@Component
public class CodeRules {

    private final CommonCodeRepository codes;

    public CodeRules(CommonCodeRepository codes) {
        this.codes = codes;
    }

    /**
     * Checks category and severity of a defect about to be written. Only a write that stores the code columns passes
     * through here (the create and the edit of a save): severity is always-active, so a row that still holds a retired
     * severity needs an active one in the same write. A write that changes no code column (a fix, a state change) does not
     * check them.
     *
     * @param current the row as it is now, or {@code null} for an insert
     */
    public void checkDefect(Defect current, CodeValue category, CodeValue severity) {
        codes.catalog(CodeGroup.DEFECT_CATEGORY).check("defect.category", category,
                current == null ? null : current.category());
        codes.catalog(CodeGroup.DEFECT_SEVERITY).check("defect.severity", severity,
                current == null ? null : current.severity());
    }
}
