package com.pathwayai.backend.requirement.repository;

import com.pathwayai.backend.requirement.entity.AcademicRequirement;
import com.pathwayai.backend.requirement.entity.RequirementType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AcademicRequirementRepository extends JpaRepository<AcademicRequirement, Long> {

    @EntityGraph(attributePaths = "source")
    List<AcademicRequirement> findByProgramIdOrderByApplicationCycleDescRequirementTypeAscIdAsc(Long programId);

    Optional<AcademicRequirement> findByProgramIdAndApplicationCycleAndRequirementTypeAndDescription(
            Long programId, String applicationCycle, RequirementType type, String description);
}
