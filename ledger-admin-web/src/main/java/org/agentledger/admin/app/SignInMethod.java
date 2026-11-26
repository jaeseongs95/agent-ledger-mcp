// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.admin.app;

/**
 * How the owner signs in to the admin web ({@code ledger.admin.sign-in}, exactly one): the fixed admin token (the personal
 * edition), OpenID Connect with the one e-mail address of the owner, or a passkey. Every owner write is approved with a passkey
 * whichever is chosen.
 */
public enum SignInMethod {
    TOKEN, OIDC, PASSKEY
}
