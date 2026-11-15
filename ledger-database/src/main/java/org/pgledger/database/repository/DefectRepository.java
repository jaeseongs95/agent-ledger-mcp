package org.pgledger.database.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.pgledger.domain.model.Defect;
import org.pgledger.domain.rule.DefectStatus;
import org.pgledger.domain.value.DefectId;
import org.pgledger.domain.value.ProjectId;
import org.springframework.data.repository.Repository;

/**
 * Defects: save (insert when the version is 0, update with the version check otherwise), read, and the number of the next case
 * ({@link CaseSequence}). No delete.
 */
public interface DefectRepository extends Repository<Defect, DefectId>, CaseSequence {

    <S extends Defect> S save(S defect);

    Optional<Defect> findById(DefectId id);

    boolean existsById(DefectId id);

    List<Defect> findByMergedInto(DefectId target);

    List<Defect> findByMergedIntoIn(Collection<DefectId> targets);

    List<Defect> findByProjectIdAndStatus(ProjectId projectId, DefectStatus status);
}
