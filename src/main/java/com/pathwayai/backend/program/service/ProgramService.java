package com.pathwayai.backend.program.service;

import static com.pathwayai.backend.program.repository.ProgramSpecifications.atLevel;
import static com.pathwayai.backend.program.repository.ProgramSpecifications.atUniversity;
import static com.pathwayai.backend.program.repository.ProgramSpecifications.fetchAssociations;
import static com.pathwayai.backend.program.repository.ProgramSpecifications.inCountry;
import static com.pathwayai.backend.program.repository.ProgramSpecifications.inField;
import static com.pathwayai.backend.program.repository.ProgramSpecifications.nameContains;
import static com.pathwayai.backend.program.repository.ProgramSpecifications.taughtIn;
import static com.pathwayai.backend.program.repository.ProgramSpecifications.tuitionAtMost;

import com.pathwayai.backend.common.exception.InvalidRequestException;
import com.pathwayai.backend.common.exception.ResourceNotFoundException;
import com.pathwayai.backend.common.response.PageResponse;
import com.pathwayai.backend.program.calendar.AcademicCalendar;
import com.pathwayai.backend.program.dto.ProgramDetailResponse;
import com.pathwayai.backend.program.dto.ProgramListItemResponse;
import com.pathwayai.backend.program.entity.DegreeProgram;
import com.pathwayai.backend.program.mapper.ProgramMapper;
import com.pathwayai.backend.program.repository.DegreeProgramRepository;
import com.pathwayai.backend.program.repository.ProgramIntakeRepository;
import com.pathwayai.backend.requirement.repository.AcademicRequirementRepository;
import com.pathwayai.backend.tuition.repository.TuitionFeeRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ProgramService {

    private final DegreeProgramRepository programs;
    private final ProgramIntakeRepository intakes;
    private final TuitionFeeRepository tuitionFees;
    private final AcademicRequirementRepository requirements;
    private final ProgramMapper mapper;
    private final AcademicCalendar calendar;

    public ProgramService(DegreeProgramRepository programs, ProgramIntakeRepository intakes,
                          TuitionFeeRepository tuitionFees, AcademicRequirementRepository requirements,
                          ProgramMapper mapper, AcademicCalendar calendar) {
        this.calendar = calendar;
        this.programs = programs;
        this.intakes = intakes;
        this.tuitionFees = tuitionFees;
        this.requirements = requirements;
        this.mapper = mapper;
    }

    public PageResponse<ProgramListItemResponse> search(ProgramSearchCriteria c, Pageable pageable) {
        if (c.tuitionMax() != null && (c.tuitionCurrency() == null || c.tuitionCurrency().isBlank())) {
            throw new InvalidRequestException("tuitionCurrency",
                    "is required when tuitionMax is given (amounts in different currencies are never compared)");
        }
        if (c.tuitionMax() == null && c.tuitionCurrency() != null) {
            throw new InvalidRequestException("tuitionMax", "is required when tuitionCurrency is given");
        }
        if (c.tuitionMax() == null && c.tuitionCategory() != null) {
            throw new InvalidRequestException("tuitionMax", "is required when tuitionCategory is given");
        }
        Specification<DegreeProgram> spec = Specification
                .where(fetchAssociations())
                .and(nameContains(c.nameQuery()))
                .and(inField(c.fieldSlug()))
                .and(atLevel(c.level()))
                .and(inCountry(c.countryCode()))
                .and(atUniversity(c.universitySlug()))
                .and(taughtIn(c.languageCode()))
                .and(tuitionAtMost(c.tuitionMax(), c.tuitionCurrency(), c.tuitionCategory(),
                        calendar.currentStartYear(), calendar.today()));
        return PageResponse.from(programs.findAll(spec, pageable), mapper::toListItem);
    }

    /** The identifier is {@code <university-slug>--<program-slug>}; slugs never contain "--". */
    public ProgramDetailResponse getByIdentifier(String identifier) {
        int split = identifier.indexOf(DegreeProgram.IDENTIFIER_SEPARATOR);
        if (split <= 0 || split + DegreeProgram.IDENTIFIER_SEPARATOR.length() >= identifier.length()) {
            throw new ResourceNotFoundException("Program", identifier);
        }
        String universitySlug = identifier.substring(0, split);
        String programSlug = identifier.substring(split + DegreeProgram.IDENTIFIER_SEPARATOR.length());
        DegreeProgram program = programs.findByUniversitySlugAndSlug(universitySlug, programSlug)
                .orElseThrow(() -> new ResourceNotFoundException("Program", identifier));
        return mapper.toDetail(
                program,
                intakes.findByProgramIdOrderByAcademicYearDescApplicationDeadlineAscIdAsc(program.getId()),
                tuitionFees.findByProgramIdOrderByAcademicYearDescStudentCategoryAsc(program.getId()),
                requirements.findByProgramIdOrderByApplicationCycleDescRequirementTypeAscIdAsc(program.getId()));
    }
}
