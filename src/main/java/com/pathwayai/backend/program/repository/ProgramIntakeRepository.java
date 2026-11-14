package com.pathwayai.backend.program.repository;

import com.pathwayai.backend.program.entity.ProgramIntake;
import com.pathwayai.backend.tuition.entity.StudentCategory;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProgramIntakeRepository extends JpaRepository<ProgramIntake, Long> {

    @EntityGraph(attributePaths = "source")
    List<ProgramIntake> findByProgramIdOrderByAcademicYearDescApplicationDeadlineAscIdAsc(Long programId);

    /** Natural key lookup for the idempotent importer; null year/category match null columns. */
    @Query("""
            select i from ProgramIntake i
            where i.program.id = :programId and i.label = :label
              and ((:year is null and i.academicYear is null) or i.academicYear = :year)
              and ((:category is null and i.applicantCategory is null) or i.applicantCategory = :category)
            """)
    Optional<ProgramIntake> findNatural(@Param("programId") Long programId, @Param("label") String label,
                                        @Param("year") String year, @Param("category") StudentCategory category);
}
