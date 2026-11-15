package org.pgledger.admin.app;

/**
 * How the owner signs in to the admin web ({@code ledger.admin.sign-in}): OpenID Connect with the one e-mail address of the
 * owner, or a passkey. Every owner write is approved with a passkey whichever is chosen.
 */
public enum SignInMethod {
    OIDC, PASSKEY
}
