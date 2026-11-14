package com.pathwayai.backend.program.mapper;

import com.pathwayai.backend.program.calendar.AcademicCalendar;
import com.pathwayai.backend.program.dto.ProgramDetailResponse;
import com.pathwayai.backend.program.dto.ProgramDetailResponse.Intake;
import com.pathwayai.backend.program.dto.ProgramDetailResponse.Requirement;
import com.pathwayai.backend.program.dto.ProgramDetailResponse.SourceRef;
import com.pathwayai.backend.program.calendar.TemporalStatus;
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

    private final AcademicCalendar calendar;

    public ProgramMapper(AcademicCalendar calendar) {
        this.calendar = calendar;
    }

    public ProgramListItemResponse toListItem(DegreeProgram p) {
        return new ProgramListItemResponse(
                p.getIdentifier(), p.getSlug(), p.getName(), p.getDegreeLevel(), field(p), p.getStudyMode(),
                p.getDurationMonths(), languages(p), p.getCampusCity(), university(p.getUniversity()),
                p.getOfficialProgramUrl(), p.getOfficialAdmissionsUrl(), activeApplicationUrl(p),
                new Verification(p.getVerificationStatus(), p.getVerifiedOn()));
    }

    /**
     * An open-ended fee ("for study rights beginning on or after D", no end stated) is only the current fee until the source
     * states a later start date for the same fee series and that date has arrived; from then on the earlier period is
     * HISTORICAL. A start date alone never proves a fee applies to a later academic year.
     */
    private TemporalStatus tuitionStatus(TuitionFee t, List<TuitionFee> all) {
        TemporalStatus status = calendar.tuition(t.getAcademicYear(), t.getEffectiveFrom(), t.getEffectiveTo());
        boolean openEnded = t.getAcademicYear() == null && t.getEffectiveTo() == null && t.getEffectiveFrom() != null;
        if (status == TemporalStatus.CURRENT && openEnded && all.stream().anyMatch(o -> o != t && o.getAcademicYear() == null
                && o.getStudentCategory() == t.getStudentCategory() && o.getBillingPeriod() == t.getBillingPeriod()
                && o.getEffectiveFrom() != null && o.getEffectiveFrom().isAfter(t.getEffectiveFrom())
                && !o.getEffectiveFrom().isAfter(calendar.today()))) {
            return TemporalStatus.HISTORICAL;
        }
        return status;
    }

    public ProgramDetailResponse toDetail(DegreeProgram p, List<ProgramIntake> intakes, List<TuitionFee> tuition,
                                          List<AcademicRequirement> requirements) {
        return new ProgramDetailResponse(
                p.getIdentifier(), p.getSlug(), p.getName(), p.getDegreeLevel(), field(p), p.getStudyMode(),
                p.getDurationMonths(), languages(p), p.getCampusCity(), university(p.getUniversity()),
                p.getOfficialProgramUrl(), p.getOfficialAdmissionsUrl(), activeApplicationUrl(p),
                new Verification(p.getVerificationStatus(), p.getVerifiedOn()),
                p.getSources().stream().map(this::source)
                        .sorted(Comparator.comparing(SourceRef::url)).toList(),
                intakes.stream().map(i -> new Intake(i.getLabel(), i.getAcademicYear(), i.getStartDate(),
                        i.getApplicationDeadline(), i.getApplicantCategory(),
                        calendar.intake(i.getAcademicYear(), i.getApplicationDeadline(), i.getStartDate()),
                        source(i.getSource()))).toList(),
                tuition.stream().map(t -> new Tuition(t.getAcademicYear(), t.getEffectiveFrom(), t.getEffectiveTo(),
                        t.getScopeNote(), t.getStudentCategory(), t.getAmount(), t.getCurrency(),
                        t.getBillingPeriod(),
                        tuitionStatus(t, tuition),
                        source(t.getSource()))).toList(),
                requirements.stream().map(r -> new Requirement(r.getApplicationCycle(), r.getRequirementType(),
                        r.getDescription(), r.getMinimumValue(), r.getValueUnit(),
                        calendar.requirement(r.getApplicationCycle()), source(r.getSource()))).toList());
    }

    /**
     * An Apply link is only active for a VERIFIED program whose link was verified for a current or upcoming
     * cycle. Anything older is withheld here, so a stale link can never be presented as an active Apply button.
     */
    private String activeApplicationUrl(DegreeProgram p) {
        if (p.getOfficialApplicationUrl() == null
                || p.getVerificationStatus() != com.pathwayai.backend.program.entity.VerificationStatus.VERIFIED
                || !calendar.isActiveCycle(p.getOfficialApplicationCycle())) {
            return null;
        }
        return p.getOfficialApplicationUrl();
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
