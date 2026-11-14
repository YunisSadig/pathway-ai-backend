package com.pathwayai.backend.tuition.repository;

import com.pathwayai.backend.tuition.entity.BillingPeriod;
import com.pathwayai.backend.tuition.entity.StudentCategory;
import com.pathwayai.backend.tuition.entity.TuitionFee;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TuitionFeeRepository extends JpaRepository<TuitionFee, Long> {

    @EntityGraph(attributePaths = "source")
    List<TuitionFee> findByProgramIdOrderByAcademicYearDescStudentCategoryAsc(Long programId);

    /** Natural key of a fee with a stated academic year. */
    Optional<TuitionFee> findByProgramIdAndAcademicYearAndStudentCategory(
            Long programId, String academicYear, StudentCategory category);

    /** Candidates for a fee whose source prints no academic year (a handful at most per program). */
    List<TuitionFee> findByProgramIdAndAcademicYearIsNullAndStudentCategoryAndBillingPeriod(
            Long programId, StudentCategory category, BillingPeriod period);

    /**
     * Natural key of a fee whose source prints no academic year: its start-date range identifies it. Matched in
     * Java because a null date cannot be bound portably as "IS NULL OR =" in a typed query parameter.
     */
    default Optional<TuitionFee> findWithoutYear(Long programId, StudentCategory category, BillingPeriod period,
                                                 LocalDate from, LocalDate to) {
        return findByProgramIdAndAcademicYearIsNullAndStudentCategoryAndBillingPeriod(programId, category, period)
                .stream()
                .filter(t -> Objects.equals(t.getEffectiveFrom(), from) && Objects.equals(t.getEffectiveTo(), to))
                .findFirst();
    }
}
