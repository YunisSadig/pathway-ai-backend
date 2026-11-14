package com.pathwayai.backend.support;

import com.pathwayai.backend.field.entity.AcademicField;
import com.pathwayai.backend.program.entity.DegreeLevel;
import com.pathwayai.backend.program.entity.DegreeProgram;
import com.pathwayai.backend.program.entity.StudyMode;
import com.pathwayai.backend.program.entity.VerificationStatus;
import com.pathwayai.backend.university.entity.University;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;

/** Synthetic program fixtures for tests only (see {@link SyntheticFixtures}). */
public final class ProgramFixtures {

    private ProgramFixtures() {
    }

    public static AcademicField field(AcademicField parent, String slug, String name) {
        return new AcademicField(parent, slug, name);
    }

    /** An UNVERIFIED program with the given languages; nothing else is known about it. */
    public static DegreeProgram program(University university, String slug, String name, DegreeLevel level,
                                        AcademicField field, String... languageCodes) {
        DegreeProgram program = new DegreeProgram(university, slug, name, level, null, null, null);
        program.describe(name, level, field, null, null, null, null,
                new LinkedHashSet<>(List.of(languageCodes)), null);
        return program;
    }

    /** Marks the program verified on a fixed synthetic date, with an application URL. */
    public static DegreeProgram verified(DegreeProgram program, String applicationUrl) {
        program.verify(VerificationStatus.VERIFIED, LocalDate.of(2026, 1, 15), applicationUrl);
        return program;
    }

    public static DegreeProgram describe(DegreeProgram p, StudyMode mode, Integer months, String programUrl) {
        p.describe(p.getName(), p.getDegreeLevel(), p.getAcademicField(), "Synthetic Campus Town", mode, months,
                "Synthetic language", p.getTeachingLanguageCodes(), programUrl);
        return p;
    }
}
