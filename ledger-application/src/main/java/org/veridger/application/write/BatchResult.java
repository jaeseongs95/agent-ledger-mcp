// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.write;

import java.util.List;

/** The result of a batch: how many operations ran and the result of each, in order. */
public record BatchResult(WriteStatus status, int count, List<WriteResult> results) {

    public BatchResult {
        results = List.copyOf(results);
    }
}
