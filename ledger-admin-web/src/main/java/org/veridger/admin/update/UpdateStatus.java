// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin.update;

/** Recorded status of the last or currently queued OS-updater run ({@code request.properties} / {@code status.properties}). */
public record UpdateStatus(State state, String targetVersion, String updatedAt, String detail) {

    public enum State {
        IDLE,
        PENDING,
        SUCCEEDED,
        ROLLED_BACK,
        RECOVERY_REQUIRED,
        FAILED
    }

    public static UpdateStatus idle() {
        return new UpdateStatus(State.IDLE, "", "", "");
    }
}
