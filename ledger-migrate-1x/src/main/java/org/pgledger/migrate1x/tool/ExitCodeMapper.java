package org.pgledger.migrate1x.tool;

import org.springframework.boot.ExitCodeExceptionMapper;
import org.springframework.stereotype.Component;

/** Boot standard path from a failed runner to the process exit code (0 means "not mine", Boot then uses 1). */
@Component
public class ExitCodeMapper implements ExitCodeExceptionMapper {

    private final ErrorTranslator translator;

    ExitCodeMapper(ErrorTranslator translator) {
        this.translator = translator;
    }

    @Override
    public int getExitCode(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ToolException refusal) {
                return translator.translate(refusal).status().code();
            }
        }
        return 0;
    }
}
