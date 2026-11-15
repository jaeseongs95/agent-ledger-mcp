package org.pgledger.archfixture;

/**
 * The enum {@link SwitchOverEnum} switches over. It is a top-level type of its own: javac makes the switch map
 * {@code SwitchOverEnum$1} only for an enum of another class (an enum nested in the switching class is switched by its
 * ordinal directly), the same way {@code DefectRelation} switches over the enum {@code CodeDirection}.
 */
public enum Side { LEFT, RIGHT }
