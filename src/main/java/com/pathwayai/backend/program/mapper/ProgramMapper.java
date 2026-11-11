package com.pathwayai.backend.program.mapper;

import com.pathwayai.backend.program.dto.ProgramDetailResponse;
import com.pathwayai.backend.program.dto.ProgramDetailResponse.Intake;
import com.pathwayai.backend.program.dto.ProgramDetailResponse.Requirement;
import com.pathwayai.backend.program.dto.ProgramDetailResponse.SourceRef;
import com.pathwayai.backend.program.dto.ProgramDetailResponse.Tuition;
import com.pathwayai.backend.program.dto.ProgramListItemResponse;
import com.pathwayai.backend.program.dto.ProgramListItemResponse.FieldRef;
import com.pathwayai.backend.program.dto.ProgramListItemResponse.UniversityRef;
import com.pathwayai.backend.program.dto.ProgramListItemResponse.Verification;
import com.pathwayai.backend.program.entity.DegreeProgram;
import com.pathwayai.backend.program.entity.ProgramIntake;
import com.pathwayai.backend.requirement.entity.AcademicRequirement;
import com.pathwayai.backend.source.entity.Source;
import com.pathwayai.backend.tuition.entity.TuitionFee;
import com.pathwayai.backend.university.entity.University;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ProgramMapper {

    public ProgramListItemResponse toListItem(DegreeProgram p) {
        return new ProgramListItemResponse(
                p.getIdentifier(), p.getSlug(), p.getName(), p.getDegreeLevel(), field(p), p.getStudyMode(),
                p.getDurationMonths(), languages(p), p.getCampusCity(), university(p.getUniversity()),
                p.getOfficialProgramUrl(), p.getOfficialApplicationUrl(),
                new Verification(p.getVerificationStatus(), p.getVerifiedOn()));
    }

    public ProgramDetailResponse toDetail(DegreeProgram p, List<ProgramIntake> intakes, List<TuitionFee> tuition,
                                          List<AcademicRequirement> requirements) {
        return new ProgramDetailResponse(
                p.getIdentifier(), p.getSlug(), p.getName(), p.getDegreeLevel(), field(p), p.getStudyMode(),
                p.getDurationMonths(), languages(p), p.getCampusCity(), university(p.getUniversity()),
                p.getOfficialProgramUrl(), p.getOfficialApplicationUrl(),
                new Verification(p.getVerificationStatus(), p.getVerifiedOn()),
                p.getSources().stream().map(this::source)
                        .sorted(Comparator.comparing(SourceRef::url)).toList(),
                intakes.stream().map(i -> new Intake(i.getLabel(), i.getAcademicYear(), i.getStartDate(),
                        i.getApplicationDeadline(), i.getApplicantCategory(), source(i.getSource()))).toList(),
                tuition.stream().map(t -> new Tuition(t.getAcademicYear(), t.getStudentCategory(), t.getAmount(),
                        t.getCurrency(), t.getBillingPeriod(), source(t.getSource()))).toList(),
                requirements.stream().map(r -> new Requirement(r.getApplicationCycle(), r.getRequirementType(),
                        r.getDescription(), r.getMinimumValue(), r.getValueUnit(), source(r.getSource()))).toList());
    }

    private static FieldRef field(DegreeProgram p) {
        var f = p.getAcademicField();
        return f == null ? null : new FieldRef(f.getSlug(), f.getName());
    }

    private static List<String> languages(DegreeProgram p) {
        return p.getTeachingLanguageCodes().stream().sorted().toList();
    }

    private static UniversityRef university(University u) {
        return new UniversityRef(u.getSlug(), u.getName(), u.getCity(), u.getCountry().getIso2Code(),
                u.getCountry().getName());
    }

    private SourceRef source(Source s) {
        return new SourceRef(s.getUrl(), s.getTitle(), s.getPublisher(), s.getSourceType(), s.getRetrievedOn());
    }
}
