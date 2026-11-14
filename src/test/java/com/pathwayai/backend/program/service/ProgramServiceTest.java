package com.pathwayai.backend.program.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.pathwayai.backend.common.exception.InvalidRequestException;
import com.pathwayai.backend.common.exception.ResourceNotFoundException;
import com.pathwayai.backend.program.mapper.ProgramMapper;
import com.pathwayai.backend.program.repository.DegreeProgramRepository;
import com.pathwayai.backend.program.repository.ProgramIntakeRepository;
import com.pathwayai.backend.requirement.repository.AcademicRequirementRepository;
import com.pathwayai.backend.tuition.entity.StudentCategory;
import com.pathwayai.backend.tuition.repository.TuitionFeeRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

class ProgramServiceTest {

    DegreeProgramRepository programs = mock(DegreeProgramRepository.class);
    com.pathwayai.backend.program.calendar.AcademicCalendar calendar =
            new com.pathwayai.backend.program.calendar.AcademicCalendar(java.time.Clock.systemUTC());
    ProgramService service = new ProgramService(programs, mock(ProgramIntakeRepository.class),
            mock(TuitionFeeRepository.class), mock(AcademicRequirementRepository.class), new ProgramMapper(calendar), calendar);

    private static ProgramSearchCriteria tuition(BigDecimal max, String currency, StudentCategory category) {
        return new ProgramSearchCriteria(null, null, null, null, null, null, max, currency, category);
    }

    @Test
    void tuitionMaxWithoutCurrencyIsRejected() {
        assertThatThrownBy(() -> service.search(tuition(BigDecimal.TEN, null, null), PageRequest.of(0, 20)))
                .isInstanceOf(InvalidRequestException.class)
                .extracting("field").isEqualTo("tuitionCurrency");
        assertThatThrownBy(() -> service.search(tuition(BigDecimal.TEN, " ", null), PageRequest.of(0, 20)))
                .isInstanceOf(InvalidRequestException.class);
        verifyNoInteractions(programs);
    }

    @Test
    void currencyOrCategoryWithoutAMaximumIsRejected() {
        assertThatThrownBy(() -> service.search(tuition(null, "EUR", null), PageRequest.of(0, 20)))
                .isInstanceOf(InvalidRequestException.class).extracting("field").isEqualTo("tuitionMax");
        assertThatThrownBy(() -> service.search(tuition(null, null, StudentCategory.DOMESTIC),
                PageRequest.of(0, 20)))
                .isInstanceOf(InvalidRequestException.class).extracting("field").isEqualTo("tuitionMax");
    }

    @Test
    void identifiersWithoutAUsableSeparatorAreNotFound() {
        for (String bad : new String[] {"plain", "--x", "x--", "x"}) {
            assertThatThrownBy(() -> service.getByIdentifier(bad)).isInstanceOf(ResourceNotFoundException.class);
        }
        verifyNoInteractions(programs);
    }
}
