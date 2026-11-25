// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.domain.model;

/**
 * The one owner as the user entity of WebAuthn: the user handle (base64url, random, made once) and the names. Every sign-in
 * identity of the admin web is this one person, so there is one row.
 *
 * @param userId      the user handle (base64url)
 * @param name        the user name
 * @param displayName what a passkey list shows (may be null)
 */
public record PasskeyUser(String userId, String name, String displayName) {
}
