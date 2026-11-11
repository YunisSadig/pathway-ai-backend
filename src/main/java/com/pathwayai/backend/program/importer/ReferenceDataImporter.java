package com.pathwayai.backend.program.importer;

import com.pathwayai.backend.country.entity.Country;
import com.pathwayai.backend.country.repository.CountryRepository;
import com.pathwayai.backend.field.entity.AcademicField;
import com.pathwayai.backend.field.repository.AcademicFieldRepository;
import com.pathwayai.backend.program.entity.DegreeProgram;
import com.pathwayai.backend.program.entity.ProgramIntake;
import com.pathwayai.backend.program.entity.VerificationStatus;
import com.pathwayai.backend.program.importer.ReferenceData.ProgramData;
import com.pathwayai.backend.program.repository.DegreeProgramRepository;
import com.pathwayai.backend.program.repository.ProgramIntakeRepository;
import com.pathwayai.backend.requirement.entity.AcademicRequirement;
import com.pathwayai.backend.requirement.repository.AcademicRequirementRepository;
import com.pathwayai.backend.source.entity.Source;
import com.pathwayai.backend.source.repository.SourceRepository;
import com.pathwayai.backend.tuition.entity.TuitionFee;
import com.pathwayai.backend.tuition.repository.TuitionFeeRepository;
import com.pathwayai.backend.university.entity.University;
import com.pathwayai.backend.university.repository.UniversityRepository;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Idempotent, additive import of source-cited reference data (countries, fields, sources, universities,
 * programs and their facts). Identity is the natural key of each entity (ISO code, slug, source URL, ...),
 * so running the same file twice changes nothing and a corrected file updates in place.
 *
 * <p>The whole file is validated first and imported in one transaction: it is applied completely or not at
 * all. The importer never deletes rows. It is deliberately separate from the Flyway migrations: schema is
 * code, data is data.
 */
@Service
public class ReferenceDataImporter {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final CountryRepository countries;
    private final AcademicFieldRepository fields;
    private final SourceRepository sources;
    private final UniversityRepository universities;
    private final DegreeProgramRepository programs;
    private final ProgramIntakeRepository intakes;
    private final TuitionFeeRepository tuitionFees;
    private final AcademicRequirementRepository requirements;

    public ReferenceDataImporter(CountryRepository countries, AcademicFieldRepository fields,
                                 SourceRepository sources, UniversityRepository universities,
                                 DegreeProgramRepository programs, ProgramIntakeRepository intakes,
                                 TuitionFeeRepository tuitionFees, AcademicRequirementRepository requirements) {
        this.countries = countries;
        this.fields = fields;
        this.sources = sources;
        this.universities = universities;
        this.programs = programs;
        this.intakes = intakes;
        this.tuitionFees = tuitionFees;
        this.requirements = requirements;
    }

    /** Rows created per entity type by one run. A repeat run of the same file reports all zeros. */
    public record Report(Map<String, Integer> created) {
        public int totalCreated() {
            return created.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    public static ReferenceData parse(InputStream in) {
        try {
            return MAPPER.readValue(in, ReferenceData.class);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Reference data could not be parsed: " + e.getMessage(), e);
        }
    }

    @Transactional
    public Report importFrom(InputStream in) throws IOException {
        try (in) {
            return importData(parse(in));
        }
    }

    @Transactional
    public Report importData(ReferenceData data) {
        List<String> errors = ReferenceDataValidator.validate(data);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Reference data rejected: " + String.join("; ", errors));
        }
        Map<String, Integer> created = new LinkedHashMap<>();
        retract(data, created);
        Map<String, Country> countryBySlug = importCountries(data, created);
        importFields(data, created);
        Map<String, Source> sourceByUrl = importSources(data, created);
        Map<String, University> universityBySlug = importUniversities(data, countryBySlug, created);
        for (ProgramData p : orEmpty(data.programs())) {
            importProgram(p, universityBySlug.get(p.university()), sourceByUrl, created);
        }
        return new Report(created);
    }

    private Map<String, Country> importCountries(ReferenceData data, Map<String, Integer> created) {
        Map<String, Country> byCode = new HashMap<>();
        for (var c : orEmpty(data.countries())) {
            Country country = countries.findByIso2Code(c.iso2()).orElse(null);
            if (country == null) {
                country = countries.save(new Country(c.iso2(), c.name()));
                count(created, "countries");
            }
            byCode.put(c.iso2(), country);
        }
        return byCode;
    }

    private void importFields(ReferenceData data, Map<String, Integer> created) {
        Map<String, AcademicField> bySlug = new HashMap<>();
        for (var f : orEmpty(data.fields())) {
            AcademicField field = fields.findBySlug(f.slug()).orElse(null);
            if (field == null) {
                field = fields.save(new AcademicField(null, f.slug(), f.name()));
                count(created, "fields");
            }
            bySlug.put(f.slug(), field);
        }
        // Second pass so a parent may be listed after its child.
        for (var f : orEmpty(data.fields())) {
            bySlug.get(f.slug()).rename(f.parent() == null ? null : bySlug.get(f.parent()), f.name());
        }
    }

    private Map<String, Source> importSources(ReferenceData data, Map<String, Integer> created) {
        Map<String, Source> byUrl = new HashMap<>();
        for (var s : orEmpty(data.sources())) {
            Source source = sources.findByUrl(s.url()).orElse(null);
            if (source == null) {
                source = sources.save(new Source(s.url(), s.title(), s.publisher(), s.type(), s.retrievedOn()));
                count(created, "sources");
            } else {
                source.refresh(s.title(), s.publisher(), s.type(), s.retrievedOn());
            }
            byUrl.put(s.url(), source);
        }
        return byUrl;
    }

    private Map<String, University> importUniversities(ReferenceData data, Map<String, Country> countryByCode,
                                                       Map<String, Integer> created) {
        Map<String, University> bySlug = new HashMap<>();
        for (var u : orEmpty(data.universities())) {
            Country country = countryByCode.get(u.country());
            University university = universities.findBySlug(u.slug()).orElse(null);
            if (university == null) {
                university = universities.save(
                        new University(country, u.slug(), u.name(), u.city(), u.websiteUrl(), u.description()));
                count(created, "universities");
            } else {
                // keep identity facts the catalogue import (or an earlier run) already stored when this file has none
                university.describe(country, u.name(),
                        u.city() != null ? u.city() : university.getCity(),
                        u.websiteUrl() != null ? u.websiteUrl() : university.getWebsiteUrl(),
                        u.description());
            }
            bySlug.put(u.slug(), university);
        }
        return bySlug;
    }

    private void importProgram(ProgramData p, University university, Map<String, Source> sourceByUrl,
                               Map<String, Integer> created) {
        DegreeProgram program = programs.findByUniversitySlugAndSlug(p.university(), p.slug()).orElse(null);
        if (program == null) {
            program = programs.save(new DegreeProgram(university, p.slug(), p.name(), p.degreeLevel(),
                    null, p.durationMonths(), p.teachingLanguage()));
            count(created, "programs");
        }
        AcademicField field = p.field() == null ? null : fields.findBySlug(p.field()).orElseThrow();
        program.describe(p.name(), p.degreeLevel(), field, p.campusCity(), p.studyMode(), p.durationMonths(),
                p.teachingLanguage(), new LinkedHashSet<>(orEmpty(p.languages())), p.officialProgramUrl(),
                p.officialAdmissionsUrl());
        var verification = p.verification();
        if (verification == null) {
            program.verify(VerificationStatus.UNVERIFIED, null, null, null);
        } else {
            program.verify(verification.status(), verification.verifiedOn(), p.officialApplicationUrl(),
                    p.officialApplicationCycle());
        }
        program.getSources().clear();
        for (String url : orEmpty(p.sources())) {
            program.getSources().add(sourceByUrl.get(url));
        }

        for (var i : orEmpty(p.intakes())) {
            Source source = sourceByUrl.get(i.source());
            ProgramIntake intake = intakes.findNatural(program.getId(), i.label(), i.academicYear(),
                    i.applicantCategory()).orElse(null);
            if (intake == null) {
                intakes.save(new ProgramIntake(program, source, i.label(), i.academicYear(), i.startDate(),
                        i.applicationDeadline(), i.applicantCategory()));
                count(created, "intakes");
            } else {
                intake.update(source, i.startDate(), i.applicationDeadline());
            }
        }
        for (var t : orEmpty(p.tuition())) {
            Source source = sourceByUrl.get(t.source());
            var existing = t.academicYear() != null
                    ? tuitionFees.findByProgramIdAndAcademicYearAndStudentCategory(
                            program.getId(), t.academicYear(), t.studentCategory())
                    : tuitionFees.findWithoutYear(program.getId(), t.studentCategory(), t.billingPeriod(),
                            t.effectiveFrom(), t.effectiveTo());
            if (existing.isEmpty()) {
                tuitionFees.save(new TuitionFee(program, source, t.academicYear(), t.studentCategory(),
                        t.amount(), t.currency(), t.billingPeriod(), t.effectiveFrom(), t.effectiveTo(),
                        t.scopeNote()));
                count(created, "tuition");
            } else {
                // A fee is keyed by the period its source states. A differing amount for the same key is a data
                // error in one of the two files, so refuse to overwrite it silently; use a retraction to
                // remove a fact that was published in error.
                var row = existing.get();
                if (row.getAmount().compareTo(t.amount()) != 0 || !row.getCurrency().equals(t.currency())
                        || row.getBillingPeriod() != t.billingPeriod()) {
                    throw new IllegalArgumentException("Tuition for " + p.university() + "--" + p.slug() + " "
                            + t.academicYear() + " " + t.studentCategory()
                            + " already exists with a different value; correct it deliberately, not by import");
                }
                row.recite(source, t.scopeNote());
            }
        }
        for (var r : orEmpty(p.requirements())) {
            Source source = sourceByUrl.get(r.source());
            var existing = requirements.findByProgramIdAndApplicationCycleAndRequirementTypeAndDescription(
                    program.getId(), r.applicationCycle(), r.type(), r.description());
            if (existing.isEmpty()) {
                requirements.save(new AcademicRequirement(program, source, r.applicationCycle(), r.type(),
                        r.description(), r.minimumValue(), r.valueUnit()));
                count(created, "requirements");
            }
        }
    }

    /** Removes facts the file explicitly retracts. Absent facts are ignored, so a re-run changes nothing. */
    private void retract(ReferenceData data, Map<String, Integer> created) {
        for (var r : data.retractions()) {
            int sep = r.program().indexOf(DegreeProgram.IDENTIFIER_SEPARATOR);
            var program = programs.findByUniversitySlugAndSlug(r.program().substring(0, sep),
                    r.program().substring(sep + DegreeProgram.IDENTIFIER_SEPARATOR.length())).orElse(null);
            if (program == null) {
                continue;
            }
            if (r.type() == ReferenceData.RetractionType.TUITION) {
                tuitionFees.findByProgramIdAndAcademicYearAndStudentCategory(
                        program.getId(), r.academicYear(), r.studentCategory()).ifPresent(row -> {
                    tuitionFees.delete(row);
                    count(created, "retracted tuition");
                });
            } else {
                intakes.findNatural(program.getId(), r.label(), r.academicYear(), r.applicantCategory())
                        .ifPresent(row -> {
                            intakes.delete(row);
                            count(created, "retracted intakes");
                        });
            }
        }
        tuitionFees.flush();
        intakes.flush();
    }

    private static void count(Map<String, Integer> created, String key) {
        created.merge(key, 1, Integer::sum);
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
