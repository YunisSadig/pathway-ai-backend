package com.pathwayai.backend.program.repository;

import com.pathwayai.backend.program.entity.DegreeProgram;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface DegreeProgramRepository
        extends JpaRepository<DegreeProgram, Long>, JpaSpecificationExecutor<DegreeProgram> {

    /** Loads everything the detail view needs from the to-one side in a single query. */
    @EntityGraph(attributePaths = {"university", "university.country", "academicField"})
    Optional<DegreeProgram> findByUniversitySlugAndSlug(String universitySlug, String slug);
}
