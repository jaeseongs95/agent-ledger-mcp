// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.domain.value;

/** A closed-set constant that has a wire token (the value the external contract uses). */
public interface Token {

    String token();
}
