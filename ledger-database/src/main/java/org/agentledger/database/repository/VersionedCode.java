// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.database.repository;

import org.agentledger.domain.model.CommonCode;

/**
 * A common code as read together with the version of its row. The service that changes the code hands the version back to
 * {@link CommonCodeRepository#update}, which writes only when the row still has it (optimistic locking by the version
 * column, the same idea as the {@code version} of a defect). The version is a fact of the stored row, not of the code, so it
 * is not a component of {@link CommonCode}: a code is equal to another code by what it says.
 */
public record VersionedCode(CommonCode code, long version) {
}
