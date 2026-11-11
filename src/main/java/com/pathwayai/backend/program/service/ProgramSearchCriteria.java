package com.pathwayai.backend.program.service;

import com.pathwayai.backend.program.entity.DegreeLevel;
import com.pathwayai.backend.tuition.entity.StudentCategory;
import java.math.BigDecimal;

/** All optional program filters. A null component means "do not filter on it". */
public record ProgramSearchCriteria(
        String nameQuery,
        String fieldSlug,
        DegreeLevel level,
        String countryCode,
        String universitySlug,
        String languageCode,
        BigDecimal tuitionMax,
        String tuitionCurrency,
        StudentCategory tuitionCategory) {

    public static ProgramSearchCriteria none() {
        return new ProgramSearchCriteria(null, null, null, null, null, null, null, null, null);
    }
}
