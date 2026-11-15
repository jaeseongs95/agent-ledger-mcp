package org.pgledger.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.admin.web.AdminProblems;
import org.springframework.context.support.StaticMessageSource;

/** HLP-003: the trace id of a server failure of the admin web comes from the injected id generator. */
class TraceIdTest {

    private static final UUID FIXED = UUID.fromString("00000000-0000-4000-8000-000000000001");

    @Test
    @DisplayName("HLP-003 the trace id is the id of the injected generator, not a random id made by the class")
    void theTraceIdComesFromTheGenerator() {
        AdminProblems problems = new AdminProblems(new StaticMessageSource(), () -> FIXED);

        assertThat(problems.newTraceId()).isEqualTo(FIXED.toString());
    }
}
