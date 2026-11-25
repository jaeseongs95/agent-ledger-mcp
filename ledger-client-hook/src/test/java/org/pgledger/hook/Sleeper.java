// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.hook;

/** A process that does nothing for ten minutes: the stand-in for a program that a stale address file names and that never answers. */
public final class Sleeper {

    private Sleeper() {}

    public static void main(String[] arguments) throws InterruptedException {
        Thread.sleep(600_000);
    }
}
