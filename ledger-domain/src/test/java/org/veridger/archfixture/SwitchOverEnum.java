// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.archfixture;

/**
 * A hand-written type whose {@code switch} over the enum {@link Side} of another file makes javac generate the synthetic
 * switch map {@code SwitchOverEnum$1}.
 */
public record SwitchOverEnum(Side side) {

    public int sign() {
        return switch (side) {
            case LEFT -> -1;
            case RIGHT -> 1;
        };
    }
}
