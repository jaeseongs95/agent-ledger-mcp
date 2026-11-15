package org.pgledger.domain.model;

import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.rule.CodeDirection;
import org.pgledger.domain.rule.CodeGroup;
import org.pgledger.domain.rule.CodeState;
import org.pgledger.domain.value.ActorId;
import org.pgledger.domain.value.CodeValue;
import org.pgledger.domain.value.TextRule;

/**
 * One entry of an extensible code list. The key (group and code) never changes and entries are never deleted;
 * an entry is retired by setting its state to INACTIVE. English text falls back to the Korean text. The direction is
 * what a relation of a RELATION_KIND code keeps of its two ends ({@link CodeDirection}); a code of another group has no
 * relation and is {@link CodeDirection#CANONICAL}, which is also what a missing direction means.
 */
public record CommonCode(CodeGroup group, CodeValue code, String nameKo, String nameEn, String descriptionKo,
        String descriptionEn, int sortOrder, CodeState state, CodeDirection direction, ActorId createdBy) {

    public CommonCode {
        nameKo = TextRule.CODE_NAME.required(nameKo);
        nameEn = nameEn == null || nameEn.isBlank() ? nameKo : TextRule.CODE_NAME.required(nameEn);
        descriptionKo = TextRule.CODE_DESCRIPTION.optional(descriptionKo);
        descriptionEn = descriptionEn == null || descriptionEn.isBlank() ? descriptionKo
                : TextRule.CODE_DESCRIPTION.optional(descriptionEn);
        direction = direction == null ? CodeDirection.CANONICAL : direction;
        if (direction == CodeDirection.GIVEN && group != CodeGroup.RELATION_KIND) {
            throw ValidationException.invalid("direction", ValidationReason.EXISTS_ONLY_FOR,
                    CodeGroup.RELATION_KIND.name());
        }
    }

    public boolean active() {
        return state == CodeState.ACTIVE;
    }
}
