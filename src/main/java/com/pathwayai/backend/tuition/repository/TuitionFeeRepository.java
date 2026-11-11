package com.pathwayai.backend.tuition.repository;

import com.pathwayai.backend.tuition.entity.StudentCategory;
import com.pathwayai.backend.tuition.entity.TuitionFee;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TuitionFeeRepository extends JpaRepository<TuitionFee, Long> {

    @EntityGraph(attributePaths = "source")
    List<TuitionFee> findByProgramIdOrderByAcademicYearDescStudentCategoryAsc(Long programId);

    Optional<TuitionFee> findByProgramIdAndAcademicYearAndStudentCategory(
            Long programId, String academicYear, StudentCategory category);
}
