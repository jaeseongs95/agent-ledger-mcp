// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.database.fake;

/** A driver class that cannot be instantiated without an argument. */
public final class NoDefaultConstructorDriver extends BaseFakeDriver {

    public NoDefaultConstructorDriver(String ignored) {
    }
}
