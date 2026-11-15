package com.pathwayai.backend.account;

import com.pathwayai.backend.account.AccountException.Violation;
import com.pathwayai.backend.account.StudentProfileDtos.Budget;
import com.pathwayai.backend.account.StudentProfileDtos.BudgetStatus;
import com.pathwayai.backend.account.StudentProfileDtos.CountryOption;
import com.pathwayai.backend.account.StudentProfileDtos.Education;
import com.pathwayai.backend.account.StudentProfileDtos.EducationStatus;
import com.pathwayai.backend.account.StudentProfileDtos.Exam;
import com.pathwayai.backend.account.StudentProfileDtos.ExamResult;
import com.pathwayai.backend.account.StudentProfileDtos.ExamSection;
import com.pathwayai.backend.account.StudentProfileDtos.ExamsStatus;
import com.pathwayai.backend.account.StudentProfileDtos.Experience;
import com.pathwayai.backend.account.StudentProfileDtos.Grade;
import com.pathwayai.backend.account.StudentProfileDtos.GradeScale;
import com.pathwayai.backend.account.StudentProfileDtos.GradeStatus;
import com.pathwayai.backend.account.StudentProfileDtos.HasOrNone;
import com.pathwayai.backend.account.StudentProfileDtos.Options;
import com.pathwayai.backend.account.StudentProfileDtos.Qualification;
import com.pathwayai.backend.account.StudentProfileDtos.ScaleOption;
import com.pathwayai.backend.account.StudentProfileDtos.Skill;
import com.pathwayai.backend.account.StudentProfileDtos.SkillOption;
import com.pathwayai.backend.account.StudentProfileDtos.SpecializationOption;
import com.pathwayai.backend.account.StudentProfileDtos.StudentProfile;
import com.pathwayai.backend.account.StudentProfileDtos.StudyArea;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The single statement of what a student profile may contain and what "complete" means. The data lives here (and in
 * {@code docs/data/grading-systems-and-exams.md}), so enabling a new country, currency or study area is a change to these lists, not to the wizard.
 *
 * <p>Two levels: {@link #lenient} runs on every save (types, ranges, supported values) so a draft can be stored half-finished; {@link #missing}
 * adds the context-aware requirements that apply when the student finishes. Nothing here converts a grade or scores a student.
 */
final class StudentProfileRules {

    /** Initial release: the five supported student countries and the currency a student there most likely thinks in (a default, never forced). */
    static final List<CountryOption> COUNTRIES = List.of(
            new CountryOption("AZ", "AZN"), new CountryOption("KZ", "KZT"), new CountryOption("GE", "GEL"),
            new CountryOption("UZ", "UZS"), new CountryOption("KG", "KGS"));
    static final Set<String> SUPPORTED = COUNTRIES.stream().map(CountryOption::code).collect(Collectors.toUnmodifiableSet());

    /** Home currencies first, then the international and European ones. USD is deliberately included although no US program is listed yet. */
    static final List<String> CURRENCIES = List.of("AZN", "KZT", "GEL", "UZS", "KGS", "EUR", "USD", "PLN", "CZK", "HUF", "RON", "DKK", "SEK");
    static final List<String> COMMON_CURRENCIES = List.of("EUR", "USD");

    static final List<String> LETTERS = List.of("A+", "A", "A-", "B+", "B", "B-", "C+", "C", "C-", "D+", "D", "D-", "E", "FX", "F");

    static final List<SpecializationOption> SPECIALIZATIONS = List.of(
            new SpecializationOption("computer_science", StudyArea.CS_IT), new SpecializationOption("software_engineering", StudyArea.CS_IT),
            new SpecializationOption("information_technology", StudyArea.CS_IT), new SpecializationOption("cybersecurity", StudyArea.CS_IT),
            new SpecializationOption("data_science", StudyArea.CS_IT), new SpecializationOption("artificial_intelligence", StudyArea.CS_IT),
            new SpecializationOption("information_systems", StudyArea.CS_IT),
            new SpecializationOption("economics", StudyArea.ECON_BUSINESS), new SpecializationOption("finance", StudyArea.ECON_BUSINESS),
            new SpecializationOption("accounting", StudyArea.ECON_BUSINESS), new SpecializationOption("business_administration", StudyArea.ECON_BUSINESS),
            new SpecializationOption("business_analytics", StudyArea.ECON_BUSINESS), new SpecializationOption("management", StudyArea.ECON_BUSINESS));

    static final List<SkillOption> SKILLS = List.of(
            new SkillOption("java", "IT"), new SkillOption("python", "IT"), new SkillOption("javascript_typescript", "IT"),
            new SkillOption("csharp", "IT"), new SkillOption("sql", "BOTH"), new SkillOption("react", "IT"), new SkillOption("spring_boot", "IT"),
            new SkillOption("cloud_computing", "IT"), new SkillOption("data_analysis", "BOTH"), new SkillOption("machine_learning", "IT"),
            new SkillOption("excel", "ECON"), new SkillOption("financial_analysis", "ECON"), new SkillOption("accounting_skill", "ECON"),
            new SkillOption("business_analysis", "ECON"), new SkillOption("statistics", "ECON"), new SkillOption("econometrics", "ECON"),
            new SkillOption("power_bi", "ECON"), new SkillOption("financial_modelling", "ECON"));
    private static final Set<String> SKILL_CODES = SKILLS.stream().map(SkillOption::code).collect(Collectors.toUnmodifiableSet());

    /** Official effective date of the new TOEFL iBT score reports (ETS, 2026-11-15 research record). */
    static final LocalDate TOEFL_NEW_SCALE_FROM = LocalDate.of(2026, 1, 21);

    private static final Map<GradeScale, BigDecimal[]> NUMERIC = Map.of(
            GradeScale.FIVE_POINT, new BigDecimal[] {BigDecimal.ONE, new BigDecimal("5")},
            GradeScale.TEN_POINT, new BigDecimal[] {BigDecimal.ONE, new BigDecimal("10")},
            GradeScale.PERCENT_100, new BigDecimal[] {BigDecimal.ZERO, new BigDecimal("100")},
            GradeScale.GPA_4, new BigDecimal[] {BigDecimal.ZERO, new BigDecimal("4")});

    private StudentProfileRules() {
    }

    static Options options() {
        List<ScaleOption> scales = List.of(
                new ScaleOption(GradeScale.FIVE_POINT, "1", "5", false), new ScaleOption(GradeScale.TEN_POINT, "1", "10", false),
                new ScaleOption(GradeScale.PERCENT_100, "0", "100", false), new ScaleOption(GradeScale.GPA_4, "0", "4", false),
                new ScaleOption(GradeScale.LETTER, null, null, true), new ScaleOption(GradeScale.OTHER, null, null, false));
        return new Options(COUNTRIES, scales, LETTERS, SPECIALIZATIONS, SKILLS, CURRENCIES, COMMON_CURRENCIES, List.of("en", "ru"),
                TOEFL_NEW_SCALE_FROM.toString());
    }

    // ------------------------------------------------------------------------------------------------ every save

    static List<Violation> lenient(StudentProfile p, LocalDate today) {
        List<Violation> v = new ArrayList<>();
        supported(v, "citizenship", p.citizenship());
        supported(v, "residence", p.residence());
        education(v, "school", p.school(), today);
        education(v, "bachelor", p.bachelor(), today);
        education(v, "master", p.master(), today);

        if (p.specialization() != null) {
            if (p.studyArea() == null) {
                v.add(new Violation("studyArea", "Choose the study area first.", "REQUIRED"));
            } else if (SPECIALIZATIONS.stream().noneMatch(s -> s.code().equals(p.specialization()) && s.area() == p.studyArea())) {
                v.add(new Violation("specialization", "Choose a specialization from the list.", "UNKNOWN_VALUE"));
            }
        }
        skills(v, p.skills());
        experiences(v, "internships", p.internshipsStatus(), p.internships(), today);
        experiences(v, "volunteering", p.volunteeringStatus(), p.volunteering(), today);
        exams(v, p, today);

        if ((p.intakeSeason() == null) != (p.intakeYear() == null)) {
            v.add(new Violation("intakeYear", "Choose both a season and a year for your preferred intake.", "INCOMPLETE_PAIR"));
        }
        budget(v, p.budget());
        return v;
    }

    private static void supported(List<Violation> v, String field, String country) {
        if (country != null && !SUPPORTED.contains(country)) {
            v.add(new Violation(field, "This country is not supported yet.", "UNSUPPORTED_COUNTRY"));
        }
    }

    private static void education(List<Violation> v, String field, Education e, LocalDate today) {
        if (e == null) {
            return;
        }
        supported(v, field + ".country", e.country());
        grade(v, field + ".grade", e.grade());
        if (e.currentYear() != null && field.equals("bachelor") && e.currentYear() > 6) {
            v.add(new Violation(field + ".currentYear", "A bachelor's program is usually 3 to 6 years.", "OUT_OF_RANGE"));
        }
        if (e.currentYear() != null && field.equals("master") && e.currentYear() > 3) {
            v.add(new Violation(field + ".currentYear", "A master's program is usually 1 to 3 years.", "OUT_OF_RANGE"));
        }
    }

    static void grade(List<Violation> v, String f, Grade g) {
        if (g == null) {
            return;
        }
        if (g.status() == GradeStatus.NOT_AVAILABLE_YET) {
            if (g.value() != null || g.letter() != null || g.scale() != null) {
                v.add(new Violation(f, "Remove the grade or choose that you have entered one.", "CONFLICT"));
            }
            return;
        }
        if (g.scale() == null) {
            v.add(new Violation(f + ".scale", "Choose how your grades are reported.", "REQUIRED"));
            return;
        }
        switch (g.scale()) {
            case LETTER -> {
                if (g.value() != null) {
                    v.add(new Violation(f + ".value", "Letter grades have no number.", "CONFLICT"));
                }
                if (g.letter() == null) {
                    v.add(new Violation(f + ".letter", "Choose your letter grade.", "REQUIRED"));
                } else if (!LETTERS.contains(g.letter())) {
                    v.add(new Violation(f + ".letter", "Choose a letter grade from the list.", "UNKNOWN_VALUE"));
                }
            }
            case OTHER -> {
                if (g.scaleLabel() == null || g.scaleLabel().isBlank()) {
                    v.add(new Violation(f + ".scaleLabel", "Name the scale, for example \"Out of 20\".", "REQUIRED"));
                }
                if (g.scaleMin() == null || g.scaleMax() == null || g.scaleMin().compareTo(g.scaleMax()) >= 0) {
                    v.add(new Violation(f + ".scaleMax", "Enter the lowest and the highest value of your scale (lowest below highest).", "INVALID_SCALE"));
                } else if (g.value() == null) {
                    v.add(new Violation(f + ".value", "Enter your grade.", "REQUIRED"));
                } else if (g.value().compareTo(g.scaleMin()) < 0 || g.value().compareTo(g.scaleMax()) > 0) {
                    v.add(new Violation(f + ".value", "Your grade must be between " + g.scaleMin().stripTrailingZeros().toPlainString() + " and "
                            + g.scaleMax().stripTrailingZeros().toPlainString() + ".", "OUT_OF_RANGE"));
                } else if (g.value().scale() > 3 && g.value().stripTrailingZeros().scale() > 3) {
                    v.add(new Violation(f + ".value", "Use at most three decimals.", "TOO_PRECISE"));
                }
            }
            default -> {
                BigDecimal[] range = NUMERIC.get(g.scale());
                if (g.value() == null) {
                    v.add(new Violation(f + ".value", "Enter your grade.", "REQUIRED"));
                } else if (g.value().compareTo(range[0]) < 0 || g.value().compareTo(range[1]) > 0) {
                    v.add(new Violation(f + ".value", "On this scale grades run from " + range[0].toPlainString() + " to "
                            + range[1].toPlainString() + ".", "OUT_OF_RANGE"));
                } else if (g.value().stripTrailingZeros().scale() > 2) {
                    v.add(new Violation(f + ".value", "Use at most two decimals.", "TOO_PRECISE"));
                }
            }
        }
    }

    private static void skills(List<Violation> v, List<Skill> skills) {
        int i = 0;
        for (Skill s : skills == null ? List.<Skill>of() : skills) {
            String f = "skills[" + i++ + "]";
            if (s.code() != null) {
                if (!SKILL_CODES.contains(s.code())) {
                    v.add(new Violation(f, "Choose a skill from the list or add your own.", "UNKNOWN_VALUE"));
                }
            } else if (s.label() == null || s.label().strip().length() < 2) {
                v.add(new Violation(f, "Name the skill (at least two characters).", "REQUIRED"));
            }
        }
    }

    private static void experiences(List<Violation> v, String field, HasOrNone status, List<Experience> list, LocalDate today) {
        List<Experience> items = list == null ? List.of() : list;
        if (status == HasOrNone.NONE && !items.isEmpty()) {
            v.add(new Violation(field, "Remove the entries or change the answer to yes.", "CONFLICT"));
        }
        int i = 0;
        for (Experience e : items) {
            String f = field + "[" + i++ + "]";
            YearMonth start = e.startMonth() == null ? null : YearMonth.parse(e.startMonth());
            YearMonth end = e.endMonth() == null ? null : YearMonth.parse(e.endMonth());
            if (Boolean.TRUE.equals(e.ongoing()) && end != null) {
                v.add(new Violation(f + ".endMonth", "An ongoing activity has no end date.", "CONFLICT"));
            }
            if (start != null && end != null && end.isBefore(start)) {
                v.add(new Violation(f + ".endMonth", "The end cannot be before the start.", "OUT_OF_RANGE"));
            }
            if (start != null && start.isAfter(YearMonth.from(today))) {
                v.add(new Violation(f + ".startMonth", "The start cannot be in the future.", "OUT_OF_RANGE"));
            }
        }
    }

    private static void exams(List<Violation> v, StudentProfile p, LocalDate today) {
        List<ExamResult> results = p.exams() == null ? List.of() : p.exams();
        if (p.examsStatus() == ExamsStatus.NOT_TAKEN && !results.isEmpty()) {
            v.add(new Violation("exams", "Remove the results or change the answer to yes.", "CONFLICT"));
        }
        Set<String> seen = new HashSet<>();
        int i = 0;
        for (ExamResult r : results) {
            String f = "exams[" + i++ + "]";
            if (!seen.add(r.exam() + "|" + r.variant() + "|" + r.testDate())) {
                v.add(new Violation(f, "This result is already listed.", "DUPLICATE"));
            }
            if (r.testDate().isAfter(today)) {
                v.add(new Violation(f + ".testDate", "The test date cannot be in the future.", "OUT_OF_RANGE"));
            }
            if (r.testDate().isBefore(LocalDate.of(1990, 1, 1))) {
                v.add(new Violation(f + ".testDate", "Check the test date.", "OUT_OF_RANGE"));
            }
            switch (r.exam()) {
                case IELTS -> {
                    variant(v, f, r, "ACADEMIC", "GENERAL");
                    half(v, f + ".overall", r.overall(), 1, 9, "IELTS bands run from 1 to 9 in half bands.");
                    sections(v, f, r, Set.of("LISTENING", "READING", "WRITING", "SPEAKING"), true, 1, 9);
                }
                case TOEFL -> {
                    variant(v, f, r, "NEW_1_6", "LEGACY_0_120");
                    if ("NEW_1_6".equals(r.variant())) {
                        if (r.testDate().isBefore(TOEFL_NEW_SCALE_FROM)) {
                            v.add(new Violation(f + ".variant", "The 1-6 report exists only for tests taken on or after 21 January 2026.", "OUT_OF_RANGE"));
                        }
                        half(v, f + ".overall", r.overall(), 1, 6, "The new TOEFL iBT scale runs from 1 to 6 in half points.");
                        sections(v, f, r, Set.of("LISTENING", "READING", "WRITING", "SPEAKING"), true, 1, 6);
                    } else if ("LEGACY_0_120".equals(r.variant())) {
                        whole(v, f + ".overall", r.overall(), 0, 120, "The earlier TOEFL iBT total runs from 0 to 120.");
                        sections(v, f, r, Set.of("LISTENING", "READING", "WRITING", "SPEAKING"), false, 0, 30);
                    }
                }
                case SAT -> {
                    if (r.variant() != null) {
                        v.add(new Violation(f + ".variant", "The SAT has no report variant.", "UNKNOWN_VALUE"));
                    }
                    whole(v, f + ".overall", r.overall(), 400, 1600, "The SAT total runs from 400 to 1600.");
                    sections(v, f, r, Set.of("MATH", "READING_WRITING"), false, 200, 800);
                }
            }
        }
    }

    private static void variant(List<Violation> v, String f, ExamResult r, String... allowed) {
        if (r.variant() == null || !List.of(allowed).contains(r.variant())) {
            v.add(new Violation(f + ".variant", "Choose the report type.", "REQUIRED"));
        }
    }

    private static void half(List<Violation> v, String f, BigDecimal x, int lo, int hi, String message) {
        BigDecimal twice = x.multiply(BigDecimal.valueOf(2));
        if (x.compareTo(BigDecimal.valueOf(lo)) < 0 || x.compareTo(BigDecimal.valueOf(hi)) > 0
                || twice.stripTrailingZeros().scale() > 0) {
            v.add(new Violation(f, message, "OUT_OF_RANGE"));
        }
    }

    private static void whole(List<Violation> v, String f, BigDecimal x, int lo, int hi, String message) {
        if (x.compareTo(BigDecimal.valueOf(lo)) < 0 || x.compareTo(BigDecimal.valueOf(hi)) > 0 || x.stripTrailingZeros().scale() > 0) {
            v.add(new Violation(f, message, "OUT_OF_RANGE"));
        }
    }

    private static void sections(List<Violation> v, String f, ExamResult r, Set<String> allowed, boolean halves, int lo, int hi) {
        Set<String> seen = new HashSet<>();
        int i = 0;
        for (ExamSection s : r.sections() == null ? List.<ExamSection>of() : r.sections()) {
            String sf = f + ".sections[" + i++ + "]";
            if (!allowed.contains(s.section()) || !seen.add(s.section())) {
                v.add(new Violation(sf, "This section does not belong to this test.", "UNKNOWN_VALUE"));
                continue;
            }
            if (halves) {
                half(v, sf + ".score", s.score(), lo, hi, "Section scores run from " + lo + " to " + hi + " in half points.");
            } else {
                whole(v, sf + ".score", s.score(), lo, hi, "Section scores run from " + lo + " to " + hi + ".");
            }
        }
    }

    private static void budget(List<Violation> v, Budget b) {
        if (b == null) {
            return;
        }
        if (b.status() == BudgetStatus.NOT_SURE) {
            if (b.amount() != null) {
                v.add(new Violation("budget.amount", "Remove the amount or choose that you have a budget.", "CONFLICT"));
            }
            return;
        }
        if (b.status() == BudgetStatus.AMOUNT) {
            if (b.amount() == null || b.amount().signum() <= 0) {
                v.add(new Violation("budget.amount", "Enter an amount greater than zero.", "REQUIRED"));
            } else if (b.amount().stripTrailingZeros().scale() > 2) {
                v.add(new Violation("budget.amount", "Use at most two decimals.", "TOO_PRECISE"));
            }
            if (b.currency() == null) {
                v.add(new Violation("budget.currency", "Choose a currency.", "REQUIRED"));
            }
        }
        if (b.currency() != null && !CURRENCIES.contains(b.currency())) {
            v.add(new Violation("budget.currency", "Choose a currency from the list.", "UNKNOWN_VALUE"));
        }
    }

    // ------------------------------------------------------------------------------------------------ completion

    /** The paths that are still required for THIS student's situation. Empty means complete. */
    static List<String> missing(StudentProfile p, boolean destinationRequired) {
        List<String> m = new ArrayList<>();
        need(m, "citizenship", p.citizenship());
        need(m, "residence", p.residence());
        need(m, "educationStatus", p.educationStatus());
        need(m, "studyArea", p.studyArea());
        need(m, "targetDegreeLevel", p.targetDegreeLevel());

        EducationStatus status = p.educationStatus();
        if (status == EducationStatus.HIGH_SCHOOL) {
            stage(m, "school", p.school(), false, true);
        } else if (status == EducationStatus.BACHELOR) {
            stage(m, "bachelor", p.bachelor(), true, true);
        } else if (status == EducationStatus.MASTER) {
            stage(m, "master", p.master(), true, true);
            stage(m, "bachelor", p.bachelor(), true, false);
        } else if (status == EducationStatus.GRADUATED) {
            need(m, "completedQualification", p.completedQualification());
            if (p.completedQualification() == Qualification.BACHELOR) {
                stage(m, "bachelor", p.bachelor(), true, false);
            } else if (p.completedQualification() == Qualification.MASTER) {
                stage(m, "master", p.master(), true, false);
            }
        }

        need(m, "internshipsStatus", p.internshipsStatus());
        if (p.internshipsStatus() == HasOrNone.HAS && (p.internships() == null || p.internships().isEmpty())) {
            m.add("internships");
        }
        need(m, "volunteeringStatus", p.volunteeringStatus());
        if (p.volunteeringStatus() == HasOrNone.HAS && (p.volunteering() == null || p.volunteering().isEmpty())) {
            m.add("volunteering");
        }
        need(m, "examsStatus", p.examsStatus());
        if (p.examsStatus() == ExamsStatus.TAKEN && (p.exams() == null || p.exams().isEmpty())) {
            m.add("exams");
        }

        if (destinationRequired && (p.destinations() == null || p.destinations().isEmpty())) {
            m.add("destinations");
        }
        if (p.budget() == null || p.budget().status() == null) {
            m.add("budget.status");
        } else if (p.budget().status() == BudgetStatus.AMOUNT) {
            if (p.budget().amount() == null) {
                m.add("budget.amount");
            }
            if (p.budget().currency() == null) {
                m.add("budget.currency");
            }
        }
        return m;
    }

    private static void stage(List<String> m, String name, Education e, boolean program, boolean current) {
        if (e == null) {
            m.add(name + ".institution");
            m.add(name + ".country");
            if (program) {
                m.add(name + ".program");
            }
            if (current) {
                m.add(name + ".currentYear");
            }
            m.add(name + ".graduationYear");
            m.add(name + ".grade");
            return;
        }
        need(m, name + ".institution", blank(e.institution()));
        need(m, name + ".country", e.country());
        if (program) {
            need(m, name + ".program", blank(e.program()));
        }
        if (current) {
            need(m, name + ".currentYear", e.currentYear());
        }
        need(m, name + ".graduationYear", e.graduationYear());
        if (e.grade() == null || e.grade().status() == null) {
            m.add(name + ".grade");
        }
    }

    private static void need(List<String> m, String path, Object value) {
        if (value == null) {
            m.add(path);
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    static String normalizeSkillLabel(String label) {
        return label.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
