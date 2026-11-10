package com.pathwayai.backend.program.repository;

import com.pathwayai.backend.program.entity.DegreeProgram;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DegreeProgramRepository extends JpaRepository<DegreeProgram, Long> {
}
