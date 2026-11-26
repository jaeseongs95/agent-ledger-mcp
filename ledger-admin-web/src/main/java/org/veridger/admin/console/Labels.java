// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin.console;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.veridger.application.read.CatalogService.CodeView;
import org.veridger.domain.rule.CheckResult;
import org.veridger.domain.rule.CodeGroup;
import org.veridger.domain.rule.CodeState;
import org.veridger.domain.rule.CriterionState;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.rule.HistoryOp;
import org.veridger.domain.rule.IssueStatus;
import org.veridger.domain.rule.Scope;
import org.veridger.domain.rule.SpecStatus;
import org.veridger.domain.rule.TaskState;
import org.veridger.domain.value.Tokens;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Resolves localized display names for closed domain enums (from the message bundles) and common codes (from each
 * code's Korean or English name according to the active request locale). Canonical wire tokens stay intact in form
 * values and URLs.
 */
@Component("labels")
public class Labels {

    private static final String CODE_MAP_ATTR = Labels.class.getName() + ".codeMap";

    private final MessageSource messages;
    private final Choices choices;

    public Labels(MessageSource messages, Choices choices) {
        this.messages = messages;
        this.choices = choices;
    }

    /** Localized label for an {@link IssueStatus} name. */
    public String issueStatus(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        Optional<IssueStatus> parsed = Tokens.find(value, IssueStatus.values());
        return parsed.isPresent() ? message("issue.status." + parsed.orElseThrow().name(), value) : value;
    }

    /** Localized label for a {@link SpecStatus} name. */
    public String specStatus(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        Optional<SpecStatus> parsed = Tokens.find(value, SpecStatus.values());
        return parsed.isPresent() ? message("spec.status." + parsed.orElseThrow().name(), value) : value;
    }

    /** Localized label for a {@link TaskState} name. */
    public String taskState(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        Optional<TaskState> parsed = Tokens.find(value, TaskState.values());
        return parsed.isPresent() ? message("task.state." + parsed.orElseThrow().name(), value) : value;
    }

    /** Localized label for a {@link CriterionState} name. */
    public String criterionState(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        Optional<CriterionState> parsed = Tokens.find(value, CriterionState.values());
        return parsed.isPresent() ? message("criterion.state." + parsed.orElseThrow().name(), value) : value;
    }

    /** Localized label for a {@link HistoryEntity} token (e.g. {@code issue}, {@code task}). */
    public String entity(String token) {
        if (!StringUtils.hasText(token)) {
            return "";
        }
        Optional<HistoryEntity> parsed = Tokens.find(token, HistoryEntity.values());
        return parsed.isPresent() ? message("entity." + parsed.orElseThrow().token(), token) : token;
    }

    /** Localized label for a {@link HistoryOp} name. */
    public String historyOp(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        Optional<HistoryOp> parsed = Tokens.find(value, HistoryOp.values());
        return parsed.isPresent() ? message("history.op." + parsed.orElseThrow().name(), value) : value;
    }

    /** Localized label for a state token on a history row, disambiguated by the row's entity token. */
    public String historyState(String entityToken, String stateValue) {
        if (!StringUtils.hasText(stateValue)) {
            return "";
        }
        Optional<HistoryEntity> entity = Tokens.find(entityToken, HistoryEntity.values());
        if (entity.isEmpty()) {
            return stateValue;
        }
        return switch (entity.orElseThrow()) {
            case ISSUE -> issueStatus(stateValue);
            case SPEC -> specStatus(stateValue);
            case TASK -> taskState(stateValue);
            case CRITERION -> criterionState(stateValue);
            default -> stateValue;
        };
    }

    /** Localized label for a {@link CodeGroup} name. */
    public String codeGroup(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        Optional<CodeGroup> parsed = Tokens.find(value, CodeGroup.values());
        return parsed.isPresent() ? message("codes.group." + parsed.orElseThrow().name(), value) : value;
    }

    /** Localized label for a {@link CodeState} name. */
    public String codeState(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        Optional<CodeState> parsed = Tokens.find(value, CodeState.values());
        return parsed.isPresent() ? message("codes.state." + parsed.orElseThrow().name(), value) : value;
    }

    /** Localized label for a deliverable {@link CheckResult} token (e.g. {@code passed}). */
    public String checkResult(String token) {
        if (!StringUtils.hasText(token)) {
            return "";
        }
        Optional<CheckResult> parsed = Tokens.find(token, CheckResult.values());
        return parsed.isPresent() ? message("deliverable.checkResult." + parsed.orElseThrow().token(), token) : token;
    }

    /** Localized label for a {@link Scope} token (e.g. {@code issue}, {@code verify}). */
    public String scope(String token) {
        if (!StringUtils.hasText(token)) {
            return "";
        }
        Optional<Scope> parsed = Tokens.find(token, Scope.values());
        return parsed.isPresent() ? message("grants.scope." + parsed.orElseThrow().token(), token) : token;
    }

    /** Localized display name of a {@link CodeView} for the active locale, falling back to the other name or raw code. */
    public String code(CodeView view) {
        if (view == null) {
            return "";
        }
        return localizedCodeName(view.code(), view.nameKo(), view.nameEn());
    }

    /**
     * Localized display name for a common code value inside the named {@link CodeGroup}, using a request-scoped lookup
     * map so rendering a full page reads {@link Choices#allCodes()} at most once per HTTP request.
     */
    public String codeName(String group, String code) {
        if (!StringUtils.hasText(code)) {
            return "";
        }
        if (!StringUtils.hasText(group)) {
            return code;
        }
        Map<String, String> map = requestCodeMap();
        return map.getOrDefault(group + "\0" + code, code);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> requestCodeMap() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            Object cached = attrs.getAttribute(CODE_MAP_ATTR, RequestAttributes.SCOPE_REQUEST);
            if (cached instanceof Map<?, ?>) {
                return (Map<String, String>) cached;
            }
        }
        Map<String, String> built = new HashMap<>();
        for (CodeView view : choices.allCodes()) {
            built.put(view.group() + "\0" + view.code(), localizedCodeName(view.code(), view.nameKo(), view.nameEn()));
        }
        if (attrs != null) {
            attrs.setAttribute(CODE_MAP_ATTR, built, RequestAttributes.SCOPE_REQUEST);
        }
        return built;
    }

    private String localizedCodeName(String code, String nameKo, String nameEn) {
        boolean korean = isKorean();
        if (korean) {
            if (StringUtils.hasText(nameKo)) {
                return nameKo;
            }
            if (StringUtils.hasText(nameEn)) {
                return nameEn;
            }
            return code;
        }
        if (StringUtils.hasText(nameEn)) {
            return nameEn;
        }
        if (StringUtils.hasText(nameKo)) {
            return nameKo;
        }
        return code;
    }

    private static boolean isKorean() {
        Locale locale = LocaleContextHolder.getLocale();
        return locale != null && "ko".equalsIgnoreCase(locale.getLanguage());
    }

    private String message(String key, String fallback) {
        Locale locale = LocaleContextHolder.getLocale();
        return messages.getMessage(key, null, fallback, locale == null ? Locale.ENGLISH : locale);
    }
}
