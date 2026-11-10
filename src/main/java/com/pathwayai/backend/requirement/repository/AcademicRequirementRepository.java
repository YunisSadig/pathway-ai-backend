package com.pathwayai.backend.requirement.repository;

import com.pathwayai.backend.requirement.entity.AcademicRequirement;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AcademicRequirementRepository extends JpaRepository<AcademicRequirement, Long> {
}
