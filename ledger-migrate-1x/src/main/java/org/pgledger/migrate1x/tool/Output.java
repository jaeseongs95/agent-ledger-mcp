package org.pgledger.migrate1x.tool;

/** The single target for what a successful command prints; refusals are not printed here, Boot reports them ({@link FailureAnalyzer}). */
public interface Output {

    void info(String text);
}
