package org.pgledger.migrate1x.support;

import java.util.ArrayList;
import java.util.List;
import org.pgledger.migrate1x.tool.Output;

/** Test double for the single console output target. */
public final class RecordingOutput implements Output {

    private final List<String> info = new ArrayList<>();

    @Override
    public void info(String text) {
        info.add(text);
    }

    public String info() {
        return String.join("\n", info);
    }
}
