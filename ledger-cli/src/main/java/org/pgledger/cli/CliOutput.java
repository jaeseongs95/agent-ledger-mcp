// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.cli;

/** The single target for what a successful command prints; refusals are not printed here, Boot reports them ({@link CliFailureAnalyzer}). */
public interface CliOutput {

    void info(String text);
}
