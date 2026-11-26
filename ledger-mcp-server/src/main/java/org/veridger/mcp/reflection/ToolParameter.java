// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.mcp.reflection;

import java.lang.reflect.Type;

/**
 * One parameter of a tool method: its wire key (the parameter name), whether the call must carry it, its type and
 * whether the type is an object or an array (then a text value is refused as the wrong type).
 */
record ToolParameter(String name, boolean required, Type type, boolean structured) {
}
