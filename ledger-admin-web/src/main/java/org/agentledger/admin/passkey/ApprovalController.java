// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.admin.passkey;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.agentledger.domain.error.AdminCode;
import org.agentledger.domain.error.AdminException;
import org.agentledger.domain.error.ValidationException;
import org.agentledger.domain.error.ValidationReason;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Asks for the approval of one owner write: the page sends what it is about to post (method, path and the fields of the form),
 * the server remembers the change and the session and answers with the options for {@code navigator.credentials.get}. The
 * assertion that comes back with the write is checked against what the server remembers ({@link ApprovalInterceptor}); the
 * fields are never trusted as a record of what was approved.
 */
@RestController
public class ApprovalController {

    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    /** One field of the form. */
    public record Field(String name, String value) {
    }

    /** The write the page is about to send. */
    public record Intent(String method, String path, List<Field> fields) {
    }

    private final ApprovalService approvals;

    public ApprovalController(ApprovalService approvals) {
        this.approvals = approvals;
    }

    @PostMapping("/approval/intent")
    ApprovalService.Options intent(@RequestBody Intent intent, HttpServletRequest request) {
        if (intent.method() == null || !WRITE_METHODS.contains(intent.method())) {
            throw ValidationException.invalid("method", ValidationReason.UNKNOWN_VALUE, String.valueOf(intent.method()));
        }
        if (intent.path() == null || !intent.path().startsWith("/") || !ApprovalInterceptor.required(intent.method(),
                intent.path())) {
            throw ValidationException.invalid("path", ValidationReason.UNKNOWN_VALUE, String.valueOf(intent.path()));
        }
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw new AdminException(AdminCode.UNAUTHORIZED, List.of());
        }
        Map<String, List<String>> parameters = new LinkedHashMap<>();
        for (Field field : intent.fields() == null ? List.<Field>of() : intent.fields()) {
            if (field.name() != null && !Objects.equals(ApprovalIntents.CSRF_FIELD, field.name())) {
                parameters.computeIfAbsent(field.name(), name -> new ArrayList<>())
                        .add(field.value() == null ? "" : field.value());
            }
        }
        return approvals.options(ApprovalIntents.binding(intent.method(), intent.path(), parameters, session.getId()));
    }
}
