// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.domain.rule;

/** The state of a row of {@code upload_staging}: still receiving bytes, or finished (the attachment exists; the row is removed by the reaper). */
public enum UploadState {
    STAGING, COMPLETED
}
