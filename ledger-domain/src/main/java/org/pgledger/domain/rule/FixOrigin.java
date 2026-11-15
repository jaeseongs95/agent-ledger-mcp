package org.pgledger.domain.rule;

/** Whether a fix row was recorded by this ledger or imported from another ledger. */
public enum FixOrigin {
    NATIVE, IMPORTED
}
