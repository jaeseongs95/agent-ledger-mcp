package org.pgledger.archfixture;

/** A hand-written type whose {@code switch} over an enum makes javac generate the synthetic switch map {@code SwitchOverEnum$1}. */
public record SwitchOverEnum(Side side) {

    /** The enum switched over. */
    public enum Side { LEFT, RIGHT }

    public int sign() {
        return switch (side) {
            case LEFT -> -1;
            case RIGHT -> 1;
        };
    }
}
