package org.pgledger.admin.passkey;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.pgledger.domain.error.AdminCode;
import org.pgledger.domain.error.AdminException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Every owner write of the admin web needs the approval of a passkey, whichever way the owner signed in: a request that changes
 * something (any method but GET, HEAD, OPTIONS and TRACE) is refused with {@code PASSKEY_APPROVAL_REQUIRED} unless it carries
 * the field {@code approval}, the assertion over the challenge that the server asked for this very change in this very session
 * ({@link ApprovalService}). The rule is the default, so a write that is added later is covered without being listed; what is
 * exempt is listed here: the request for the approval itself, the preview of the grant screen (it stores nothing) and the two
 * routes that only refuse to delete a common code. Reads need no approval. The CLI on the server (the owner's shell) does not
 * pass through the admin web and is unchanged.
 */
@Component
public class ApprovalInterceptor implements HandlerInterceptor {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private static final Set<String> EXEMPT_PATHS = Set.of("/approval/intent", "/grants/preview", "/codes/delete");
    private static final String CODES = "/codes";

    private final ApprovalService approvals;

    public ApprovalInterceptor(ApprovalService approvals) {
        this.approvals = approvals;
    }

    /** Whether a request with this method and path (without the context path) needs the approval of a passkey. */
    public static boolean required(String method, String path) {
        if (SAFE_METHODS.contains(method) || EXEMPT_PATHS.contains(path)) {
            return false;
        }
        return !(Objects.equals("DELETE", method) && Objects.equals(CODES, path));
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!required(request.getMethod(), path)) {
            return true;
        }
        HttpSession session = request.getSession(false);
        String approval = request.getParameter(ApprovalIntents.APPROVAL_FIELD);
        if (session == null || !StringUtils.hasText(approval)) {
            throw new AdminException(AdminCode.PASSKEY_APPROVAL_REQUIRED, List.of());
        }
        approvals.verify(approval, ApprovalIntents.binding(request.getMethod(), path, parameters(request.getParameterMap()),
                session.getId()));
        return true;
    }

    private static Map<String, List<String>> parameters(Map<String, String[]> parameters) {
        return parameters.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> Arrays.asList(entry.getValue())));
    }
}
