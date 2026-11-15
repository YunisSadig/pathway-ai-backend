package com.pathwayai.backend.account;

import com.pathwayai.backend.account.AccountException.Violation;
import com.pathwayai.backend.account.StudentProfileDtos.AvatarInfo;
import com.pathwayai.backend.account.StudentProfileDtos.Budget;
import com.pathwayai.backend.account.StudentProfileDtos.BudgetStatus;
import com.pathwayai.backend.account.StudentProfileDtos.Completeness;
import com.pathwayai.backend.account.StudentProfileDtos.Destinations;
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
import com.pathwayai.backend.account.StudentProfileDtos.IntakeSeason;
import com.pathwayai.backend.account.StudentProfileDtos.Legacy;
import com.pathwayai.backend.account.StudentProfileDtos.Onboarding;
import com.pathwayai.backend.account.StudentProfileDtos.Qualification;
import com.pathwayai.backend.account.StudentProfileDtos.Skill;
import com.pathwayai.backend.account.StudentProfileDtos.StudentProfile;
import com.pathwayai.backend.account.StudentProfileDtos.StudentProfileView;
import com.pathwayai.backend.account.StudentProfileDtos.StudyArea;
import com.pathwayai.backend.account.StudentProfileDtos.TargetLevel;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes ONE account's student profile (v2). The account id always comes from the session. A draft may be saved half-finished
 * (only types, ranges and supported values are checked); once the student has finished onboarding, every save must keep the profile
 * complete for their situation. Nothing here converts a grade, estimates an equivalence or scores a student.
 */
@Service
class StudentProfileService {

    private final JdbcClient jdbc;
    private final DestinationService destinations;
    private final AccountService accounts;
    private final ProfileService legacy;
    private final Clock clock;

    StudentProfileService(JdbcClient jdbc, DestinationService destinations, AccountService accounts, ProfileService legacy, Clock clock) {
        this.jdbc = jdbc;
        this.destinations = destinations;
        this.accounts = accounts;
        this.legacy = legacy;
        this.clock = clock;
    }

    StudentProfileView get(UUID id) {
        return view(id, destinations.forAccount(id));
    }

    @Transactional
    StudentProfileView save(UUID id, StudentProfile p) {
        Destinations dest = destinations.forAccount(id);
        List<Violation> problems = new ArrayList<>(StudentProfileRules.lenient(p, today()));
        destinationChecks(problems, p, dest);
        String status = jdbc.sql("select onboarding_status from student_profile where account_id = :id").param("id", id)
                .query(String.class).optional().orElse("NOT_STARTED");
        if ("COMPLETED".equals(status)) {
            // a finished profile never becomes incomplete again: editing is allowed, emptying a required answer is not
            for (String path : StudentProfileRules.missing(p, destinationRequired(dest))) {
                problems.add(new Violation(path, "This is required.", "REQUIRED"));
            }
        }
        if (!problems.isEmpty()) {
            throw new AccountException(400, "VALIDATION", "Some profile fields need attention.", problems);
        }
        persist(id, p, "COMPLETED".equals(status) ? "COMPLETED" : "IN_PROGRESS");
        return view(id, dest);
    }

    @Transactional
    StudentProfileView complete(UUID id) {
        Destinations dest = destinations.forAccount(id);
        StudentProfile stored = view(id, dest).profile();
        List<Violation> problems = new ArrayList<>(StudentProfileRules.lenient(stored, today()));
        for (String path : StudentProfileRules.missing(stored, destinationRequired(dest))) {
            problems.add(new Violation(path, "This is required.", "REQUIRED"));
        }
        if (!problems.isEmpty()) {
            throw new AccountException(400, "INCOMPLETE", "Some answers are still missing.", problems);
        }
        jdbc.sql("update student_profile set onboarding_status = 'COMPLETED', onboarding_step = 6, onboarding_completed_at = now(), updated_at = now() "
                + "where account_id = :id").param("id", id).update();
        return view(id, dest);
    }

    /** Language preference only (Settings): a small independent write that never touches the rest of the profile. */
    @Transactional
    void setLanguage(UUID id, String language) {
        if (!"en".equals(language) && !"ru".equals(language)) {
            throw new AccountException(400, "VALIDATION", "Choose English or Russian.",
                    List.of(new Violation("language", "Choose English or Russian.", "UNKNOWN_VALUE")));
        }
        jdbc.sql("insert into student_profile (account_id, ui_language) values (:id, :l) on conflict (account_id) do update set ui_language = :l, updated_at = now()")
                .param("id", id).param("l", language).update();
    }

    // ---------------------------------------------------------------------------------------------- reading

    private StudentProfileView view(UUID id, Destinations dest) {
        var row = jdbc.sql("select * from student_profile where account_id = :id").param("id", id).query((rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            for (String c : List.of("current_country", "citizenship_country", "education_status", "completed_qualification", "study_area",
                    "specialization", "internships_status", "volunteering_status", "exams_status", "budget_status", "ui_language",
                    "onboarding_status", "target_degree_level", "intake_season", "max_annual_tuition_currency", "current_university",
                    "field_of_study")) {
                m.put(c, rs.getString(c));
            }
            m.put("intake_year", rs.getObject("intake_year"));
            m.put("onboarding_step", rs.getInt("onboarding_step"));
            m.put("max_annual_tuition", rs.getBigDecimal("max_annual_tuition"));
            m.put("gpa", rs.getBigDecimal("gpa"));
            m.put("gpa_scale_max", rs.getBigDecimal("gpa_scale_max"));
            m.put("updated_at", rs.getTimestamp("updated_at"));
            return m;
        }).optional();

        Map<String, Education> stages = new LinkedHashMap<>();
        jdbc.sql("select * from student_education where account_id = :id").param("id", id).query((rs, n) -> {
            String grade = rs.getString("grade_status");
            Grade g = grade == null ? null : new Grade(GradeStatus.valueOf(grade), enumOrNull(GradeScale.class, rs.getString("grade_scale")),
                    rs.getBigDecimal("grade_value"), rs.getString("grade_letter"), rs.getString("scale_label"), rs.getBigDecimal("scale_min"),
                    rs.getBigDecimal("scale_max"));
            stages.put(rs.getString("stage"), new Education(rs.getString("institution"), rs.getString("country"), rs.getString("program"),
                    (Integer) rs.getObject("current_year"), (Integer) rs.getObject("graduation_year"), g));
            return null;
        }).list();

        List<Skill> skills = jdbc.sql("select skill_key, label from student_skill where account_id = :id order by skill_key").param("id", id)
                .query((rs, n) -> rs.getString(1).startsWith("custom:") ? new Skill(null, rs.getString(2)) : new Skill(rs.getString(1), null)).list();
        List<Experience> internships = experiences(id, "INTERNSHIP");
        List<Experience> volunteering = experiences(id, "VOLUNTEERING");
        List<ExamResult> exams = exams(id);
        List<String> destinationCodes = jdbc.sql("select country_code from student_destination_preference where account_id = :id order by country_code")
                .param("id", id).query(String.class).list();
        List<String> preferred = jdbc.sql("select university_slug from student_preferred_university where account_id = :id order by university_slug")
                .param("id", id).query(String.class).list();
        AvatarInfo avatar = jdbc.sql("select version from student_avatar where account_id = :id").param("id", id).query(Integer.class).optional()
                .map(version -> new AvatarInfo(true, version)).orElse(new AvatarInfo(false, 0));

        Map<String, Object> r = row.orElse(Map.of());
        Budget budget = r.get("budget_status") == null ? null : new Budget(BudgetStatus.valueOf((String) r.get("budget_status")),
                (BigDecimal) r.get("max_annual_tuition"), (String) r.get("max_annual_tuition_currency"));
        StudentProfile p = new StudentProfile((String) r.get("citizenship_country"), (String) r.get("current_country"),
                enumOrNull(EducationStatus.class, (String) r.get("education_status")),
                enumOrNull(Qualification.class, (String) r.get("completed_qualification")),
                stages.get("SCHOOL"), stages.get("BACHELOR"), stages.get("MASTER"), enumOrNull(StudyArea.class, (String) r.get("study_area")),
                (String) r.get("specialization"), skills, enumOrNull(HasOrNone.class, (String) r.get("internships_status")), internships,
                enumOrNull(HasOrNone.class, (String) r.get("volunteering_status")), volunteering,
                enumOrNull(ExamsStatus.class, (String) r.get("exams_status")), exams,
                enumOrNull(TargetLevel.class, (String) r.get("target_degree_level")), enumOrNull(IntakeSeason.class, (String) r.get("intake_season")),
                (Integer) r.get("intake_year"), destinationCodes, preferred, budget, (String) r.get("ui_language"),
                new Onboarding(r.get("onboarding_status") == null ? "NOT_STARTED" : (String) r.get("onboarding_status"),
                        r.get("onboarding_step") == null ? 0 : (Integer) r.get("onboarding_step")));

        List<String> missing = StudentProfileRules.missing(p, destinationRequired(dest));
        Legacy old = row.isPresent() && (r.get("gpa") != null || r.get("current_university") != null || r.get("field_of_study") != null)
                ? new Legacy((BigDecimal) r.get("gpa"), (BigDecimal) r.get("gpa_scale_max"), (String) r.get("current_university"),
                        (String) r.get("field_of_study"), legacy.get(id).languageQualifications())
                : null;
        Timestamp updated = (Timestamp) r.get("updated_at");
        return new StudentProfileView(p, avatar, new Completeness(missing.isEmpty(), missing), old, updated == null ? null : updated.toInstant());
    }

    private List<Experience> experiences(UUID id, String kind) {
        return jdbc.sql("select organization, role, field, start_month, end_month, ongoing, description from student_experience "
                + "where account_id = :id and kind = :k order by start_month desc nulls last, id").param("id", id).param("k", kind)
                .query((rs, n) -> new Experience(rs.getString(1), rs.getString(2), rs.getString(3), month(rs.getDate(4)), month(rs.getDate(5)),
                        rs.getBoolean(6), rs.getString(7))).list();
    }

    private List<ExamResult> exams(UUID id) {
        Map<Long, ExamResult> byId = new LinkedHashMap<>();
        Map<Long, List<ExamSection>> sections = new LinkedHashMap<>();
        jdbc.sql("select id, exam, variant, test_date, overall from student_exam_result where account_id = :id order by test_date desc, id")
                .param("id", id).query((rs, n) -> {
                    long rid = rs.getLong(1);
                    sections.put(rid, new ArrayList<>());
                    byId.put(rid, new ExamResult(Exam.valueOf(rs.getString(2)), rs.getString(3), rs.getDate(4).toLocalDate(), rs.getBigDecimal(5),
                            sections.get(rid)));
                    return null;
                }).list();
        if (!byId.isEmpty()) {
            jdbc.sql("select s.result_id, s.section, s.score from student_exam_section s join student_exam_result r on r.id = s.result_id "
                    + "where r.account_id = :id order by s.section").param("id", id).query((rs, n) -> {
                        sections.get(rs.getLong(1)).add(new ExamSection(rs.getString(2), rs.getBigDecimal(3)));
                        return null;
                    }).list();
        }
        return new ArrayList<>(byId.values());
    }

    // ---------------------------------------------------------------------------------------------- writing

    private void persist(UUID id, StudentProfile p, String status) {
        Budget b = p.budget();
        Onboarding o = p.onboarding();
        int step = o == null || o.step() == null ? 0 : o.step();
        jdbc.sql("""
                insert into student_profile (account_id, citizenship_country, current_country, education_status, completed_qualification, study_area,
                    specialization, internships_status, volunteering_status, exams_status, budget_status, max_annual_tuition,
                    max_annual_tuition_currency, target_degree_level, intake_season, intake_year, ui_language, onboarding_status, onboarding_step)
                values (:id, :cit, :res, :edu, :qual, :area, :spec, :intern, :vol, :exams, :bstatus, :amount, :currency, :target, :season, :year,
                    :lang, :status, :step)
                on conflict (account_id) do update set citizenship_country = excluded.citizenship_country,
                    current_country = excluded.current_country, education_status = excluded.education_status,
                    completed_qualification = excluded.completed_qualification, study_area = excluded.study_area,
                    specialization = excluded.specialization, internships_status = excluded.internships_status,
                    volunteering_status = excluded.volunteering_status, exams_status = excluded.exams_status,
                    budget_status = excluded.budget_status, max_annual_tuition = excluded.max_annual_tuition,
                    max_annual_tuition_currency = excluded.max_annual_tuition_currency, target_degree_level = excluded.target_degree_level,
                    intake_season = excluded.intake_season, intake_year = excluded.intake_year,
                    ui_language = coalesce(excluded.ui_language, student_profile.ui_language),
                    onboarding_status = excluded.onboarding_status, onboarding_step = excluded.onboarding_step, updated_at = now()""")
                .param("id", id).param("cit", p.citizenship()).param("res", p.residence()).param("edu", name(p.educationStatus()))
                .param("qual", name(p.completedQualification())).param("area", name(p.studyArea())).param("spec", p.specialization())
                .param("intern", name(p.internshipsStatus())).param("vol", name(p.volunteeringStatus())).param("exams", name(p.examsStatus()))
                .param("bstatus", b == null ? null : name(b.status())).param("amount", b == null || b.status() != BudgetStatus.AMOUNT ? null : b.amount())
                .param("currency", b == null || b.status() != BudgetStatus.AMOUNT ? null : b.currency())
                .param("target", name(p.targetDegreeLevel())).param("season", name(p.intakeSeason())).param("year", p.intakeYear())
                .param("lang", p.language()).param("status", status).param("step", step).update();

        for (String table : List.of("student_education", "student_skill", "student_experience", "student_exam_result",
                "student_destination_preference", "student_preferred_university")) {
            jdbc.sql("delete from " + table + " where account_id = :id").param("id", id).update(); // section rows go with their result (cascade)
        }
        stage(id, "SCHOOL", p.school());
        stage(id, "BACHELOR", p.bachelor());
        stage(id, "MASTER", p.master());

        Set<String> keys = new HashSet<>();
        for (Skill s : nullToEmpty(p.skills())) {
            String key = s.code() != null ? s.code() : "custom:" + StudentProfileRules.normalizeSkillLabel(s.label());
            if (key.length() <= 70 && keys.add(key)) {
                jdbc.sql("insert into student_skill (account_id, skill_key, label) values (:id, :k, :l)").param("id", id).param("k", key)
                        .param("l", s.code() == null ? s.label().strip() : null).update();
            }
        }
        experiences(id, "INTERNSHIP", p.internshipsStatus() == HasOrNone.NONE ? List.of() : nullToEmpty(p.internships()));
        experiences(id, "VOLUNTEERING", p.volunteeringStatus() == HasOrNone.NONE ? List.of() : nullToEmpty(p.volunteering()));
        if (p.examsStatus() != ExamsStatus.NOT_TAKEN) {
            for (ExamResult r : nullToEmpty(p.exams())) {
                var keys2 = new GeneratedKeyHolder();
                jdbc.sql("insert into student_exam_result (account_id, exam, variant, test_date, overall) values (:id, :e, :v, :d, :o)")
                        .param("id", id).param("e", r.exam().name()).param("v", r.variant()).param("d", Date.valueOf(r.testDate())).param("o", r.overall())
                        .update(keys2, "id");
                Number rid = (Number) keys2.getKeys().get("id");
                for (ExamSection s : nullToEmpty(r.sections())) {
                    jdbc.sql("insert into student_exam_section (result_id, section, score) values (:r, :s, :c)").param("r", rid.longValue())
                            .param("s", s.section()).param("c", s.score()).update();
                }
            }
        }
        for (String c : new HashSet<>(nullToEmpty(p.destinations()))) {
            jdbc.sql("insert into student_destination_preference (account_id, country_code) values (:id, :c)").param("id", id).param("c", c).update();
        }
        for (String s : new HashSet<>(nullToEmpty(p.preferredUniversities()))) {
            jdbc.sql("insert into student_preferred_university (account_id, university_slug) values (:id, :s)").param("id", id).param("s", s).update();
        }
    }

    private void stage(UUID id, String stage, Education e) {
        if (e == null) {
            return;
        }
        Grade g = e.grade();
        boolean entered = g != null && g.status() == GradeStatus.ENTERED;
        jdbc.sql("""
                insert into student_education (account_id, stage, institution, country, program, current_year, graduation_year, grade_status,
                    grade_scale, grade_value, grade_letter, scale_label, scale_min, scale_max)
                values (:id, :stage, :inst, :country, :program, :cy, :gy, :gs, :scale, :value, :letter, :label, :min, :max)""")
                .param("id", id).param("stage", stage).param("inst", blank(e.institution())).param("country", e.country())
                .param("program", blank(e.program())).param("cy", e.currentYear()).param("gy", e.graduationYear())
                .param("gs", g == null ? null : g.status().name()).param("scale", entered ? name(g.scale()) : null)
                .param("value", entered && g.scale() != GradeScale.LETTER ? g.value() : null)
                .param("letter", entered && g.scale() == GradeScale.LETTER ? g.letter() : null)
                .param("label", entered && g.scale() == GradeScale.OTHER ? blank(g.scaleLabel()) : null)
                .param("min", entered && g.scale() == GradeScale.OTHER ? g.scaleMin() : null)
                .param("max", entered && g.scale() == GradeScale.OTHER ? g.scaleMax() : null).update();
    }

    private void experiences(UUID id, String kind, List<Experience> items) {
        for (Experience e : items) {
            jdbc.sql("insert into student_experience (account_id, kind, organization, role, field, start_month, end_month, ongoing, description) "
                    + "values (:id, :k, :o, :r, :f, :s, :e, :on, :d)").param("id", id).param("k", kind).param("o", e.organization().strip())
                    .param("r", e.role().strip()).param("f", blank(e.field())).param("s", firstDay(e.startMonth())).param("e", firstDay(e.endMonth()))
                    .param("on", Boolean.TRUE.equals(e.ongoing())).param("d", blank(e.description())).update();
        }
    }

    private void destinationChecks(List<Violation> problems, StudentProfile p, Destinations dest) {
        List<String> chosen = nullToEmpty(p.destinations());
        List<String> universities = nullToEmpty(p.preferredUniversities());
        if (dest.restricted()) {
            if (!chosen.isEmpty() || !universities.isEmpty()) {
                problems.add(new Violation("destinations", "The university catalogue is in a protected beta for your account, so destinations cannot be chosen yet.",
                        "CATALOGUE_PROTECTED"));
            }
            return;
        }
        Set<String> countries = new HashSet<>();
        dest.countries().forEach(c -> countries.add(c.code()));
        for (String c : chosen) {
            if (!countries.contains(c)) {
                problems.add(new Violation("destinations", "This destination is not available yet.", "DESTINATION_UNAVAILABLE"));
            }
        }
        Set<String> slugs = new HashSet<>();
        dest.universities().forEach(u -> slugs.add(u.slug()));
        for (String s : universities) {
            if (!slugs.contains(s)) {
                problems.add(new Violation("preferredUniversities", "This university is not available.", "DESTINATION_UNAVAILABLE"));
            }
        }
    }

    private static boolean destinationRequired(Destinations dest) {
        return !dest.restricted() && !dest.countries().isEmpty();
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static Date firstDay(String yearMonth) {
        return yearMonth == null ? null : Date.valueOf(YearMonth.parse(yearMonth).atDay(1));
    }

    private static String month(Date d) {
        return d == null ? null : YearMonth.from(d.toLocalDate()).toString();
    }

    private static String name(Enum<?> e) {
        return e == null ? null : e.name();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }
}
