// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin.app;

/** What the application can do: the whole console, or (no usable JDBC driver) only login and driver registration. */
public enum AdminMode {
    CONSOLE, SETUP
}
