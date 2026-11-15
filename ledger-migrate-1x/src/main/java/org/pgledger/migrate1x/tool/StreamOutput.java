package org.pgledger.migrate1x.tool;

import java.io.PrintStream;
import org.springframework.stereotype.Component;

/** The only place that touches the process standard streams: the output of every command goes to standard output. */
@Component
final class StreamOutput implements Output {

    private final PrintStream info;

    StreamOutput() {
        this.info = System.out;
    }

    @Override
    public void info(String text) {
        info.println(text);
    }
}
