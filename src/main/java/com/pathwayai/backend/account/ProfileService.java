package com.pathwayai.backend.account;

import com.pathwayai.backend.account.ProfileDtos.CurrentLevel;
import com.pathwayai.backend.account.ProfileDtos.IntakeSeason;
import com.pathwayai.backend.account.ProfileDtos.LanguageQualification;
import com.pathwayai.backend.account.ProfileDtos.LanguageTest;
import com.pathwayai.backend.account.ProfileDtos.ProfileRequest;
import com.pathwayai.backend.account.ProfileDtos.ProfileResponse;
import com.pathwayai.backend.account.ProfileDtos.TargetLevel;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads and replaces ONE account's profile. The account id always comes from the session, never from the request. */
@Service
class ProfileService {

    private static final Set<String> COUNTRIES = Set.of(Locale.getISOCountries());
    private static final Map<LanguageTest, BigDecimal[]> RANGES = Map.of(
            LanguageTest.IELTS, new BigDecimal[] {BigDecimal.ZERO, new BigDecimal("9")},
            LanguageTest.TOEFL_IBT, new BigDecimal[] {BigDecimal.ZERO, new BigDecimal("120")},
            LanguageTest.PTE, new BigDecimal[] {new BigDecimal("10"), new BigDecimal("90")},
            LanguageTest.DUOLINGO, new BigDecimal[] {new BigDecimal("10"), new BigDecimal("160")},
            LanguageTest.CAMBRIDGE, new BigDecimal[] {new BigDecimal("80"), new BigDecimal("230")});

    private final JdbcClient jdbc;

    ProfileService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    ProfileResponse get(UUID accountId) {
        var row = jdbc.sql("select * from student_profile where account_id = :id").param("id", accountId).query((rs, n) -> {
            Timestamp updated = rs.getTimestamp("updated_at");
            return new Object[] {rs.getString("current_country"), rs.getString("current_university"), rs.getString("current_degree_level"),
                    rs.getString("field_of_study"), rs.getBigDecimal("gpa"), rs.getBigDecimal("gpa_scale_max"),
                    rs.getString("target_degree_level"), rs.getString("intake_season"), (Integer) rs.getObject("intake_year"),
                    rs.getBigDecimal("max_annual_tuition"), rs.getString("max_annual_tuition_currency"), updated.toInstant()};
        }).optional();
        List<LanguageQualification> languages = jdbc.sql(
                "select test, test_name, overall from student_language_qualification where account_id = :id order by test")
                .param("id", accountId).query((rs, n) -> new LanguageQualification(LanguageTest.valueOf(rs.getString("test")),
                        rs.getString("test_name"), rs.getBigDecimal("overall"))).list();
        List<String> destinations = jdbc.sql("select country_code from student_destination_preference where account_id = :id order by country_code")
                .param("id", accountId).query(String.class).list();
        if (row.isEmpty()) {
            return new ProfileResponse(null, null, null, null, null, null, null, null, null, null, null, languages, destinations, null);
        }
        Object[] r = row.get();
        return new ProfileResponse((String) r[0], (String) r[1], enumOrNull(CurrentLevel.class, (String) r[2]), (String) r[3], (BigDecimal) r[4],
                (BigDecimal) r[5], enumOrNull(TargetLevel.class, (String) r[6]), enumOrNull(IntakeSeason.class, (String) r[7]), (Integer) r[8],
                (BigDecimal) r[9], (String) r[10], languages, destinations, (java.time.Instant) r[11]);
    }

    @Transactional
    ProfileResponse replace(UUID accountId, ProfileRequest p) {
        List<AccountException.Violation> problems = check(p);
        if (!problems.isEmpty()) {
            throw new AccountException(400, "VALIDATION", "Some profile fields need attention.", problems);
        }
        jdbc.sql("""
                insert into student_profile (account_id, current_country, current_university, current_degree_level, field_of_study, gpa,
                    gpa_scale_max, target_degree_level, intake_season, intake_year, max_annual_tuition, max_annual_tuition_currency)
                values (:id, :country, :uni, :level, :field, :gpa, :scale, :target, :season, :year, :budget, :currency)
                on conflict (account_id) do update set current_country = excluded.current_country,
                    current_university = excluded.current_university, current_degree_level = excluded.current_degree_level,
                    field_of_study = excluded.field_of_study, gpa = excluded.gpa, gpa_scale_max = excluded.gpa_scale_max,
                    target_degree_level = excluded.target_degree_level, intake_season = excluded.intake_season,
                    intake_year = excluded.intake_year, max_annual_tuition = excluded.max_annual_tuition,
                    max_annual_tuition_currency = excluded.max_annual_tuition_currency, updated_at = now()""")
                .param("id", accountId).param("country", blank(p.currentCountry())).param("uni", blank(p.currentUniversity()))
                .param("level", p.currentDegreeLevel() == null ? null : p.currentDegreeLevel().name()).param("field", blank(p.fieldOfStudy()))
                .param("gpa", p.gpa()).param("scale", p.gpaScaleMax())
                .param("target", p.targetDegreeLevel() == null ? null : p.targetDegreeLevel().name())
                .param("season", p.intakeSeason() == null ? null : p.intakeSeason().name()).param("year", p.intakeYear())
                .param("budget", p.maxAnnualTuition()).param("currency", blank(p.maxAnnualTuitionCurrency())).update();
        jdbc.sql("delete from student_language_qualification where account_id = :id").param("id", accountId).update();
        jdbc.sql("delete from student_destination_preference where account_id = :id").param("id", accountId).update();
        for (LanguageQualification q : nullToEmpty(p.languageQualifications())) {
            jdbc.sql("insert into student_language_qualification (account_id, test, test_name, overall) values (:id, :t, :n, :o)")
                    .param("id", accountId).param("t", q.test().name()).param("n", q.test() == LanguageTest.OTHER ? blank(q.testName()) : null)
                    .param("o", q.overall()).update();
        }
        for (String country : new HashSet<>(nullToEmpty(p.preferredDestinations()))) {
            jdbc.sql("insert into student_destination_preference (account_id, country_code) values (:id, :c)")
                    .param("id", accountId).param("c", country).update();
        }
        return get(accountId);
    }

    /** Privacy control: removes everything the student entered, keeping the account itself. */
    @Transactional
    void clear(UUID accountId) {
        jdbc.sql("delete from student_profile where account_id = :id").param("id", accountId).update();
        jdbc.sql("delete from student_language_qualification where account_id = :id").param("id", accountId).update();
        jdbc.sql("delete from student_destination_preference where account_id = :id").param("id", accountId).update();
        for (String table : List.of("student_education", "student_skill", "student_experience", "student_exam_result", "student_preferred_university",
                "student_avatar")) {
            jdbc.sql("delete from " + table + " where account_id = :id").param("id", accountId).update();
        }
    }

    private static List<AccountException.Violation> check(ProfileRequest p) {
        List<AccountException.Violation> v = new ArrayList<>();
        if (p.currentCountry() != null && !p.currentCountry().isBlank() && !COUNTRIES.contains(p.currentCountry())) {
            v.add(new AccountException.Violation("currentCountry", "Choose a country from the list."));
        }
        if ((p.gpa() == null) != (p.gpaScaleMax() == null)) {
            v.add(new AccountException.Violation("gpa", "Enter both your grade and the maximum of your grading scale."));
        } else if (p.gpa() != null && p.gpa().compareTo(p.gpaScaleMax()) > 0) {
            v.add(new AccountException.Violation("gpa", "Your grade cannot be higher than the maximum of the scale."));
        }
        if ((p.intakeSeason() == null) != (p.intakeYear() == null)) {
            v.add(new AccountException.Violation("intakeYear", "Choose both a season and a year for your preferred intake."));
        }
        if (p.maxAnnualTuition() != null) {
            String currency = blank(p.maxAnnualTuitionCurrency());
            if (currency == null) {
                v.add(new AccountException.Violation("maxAnnualTuitionCurrency", "Choose a currency for your budget."));
            } else if (!validCurrency(currency)) {
                v.add(new AccountException.Violation("maxAnnualTuitionCurrency", "Choose a currency from the list."));
            }
        } else if (blank(p.maxAnnualTuitionCurrency()) != null) {
            v.add(new AccountException.Violation("maxAnnualTuition", "Enter the budget amount or remove the currency."));
        }
        Set<LanguageTest> seen = new HashSet<>();
        int i = 0;
        for (LanguageQualification q : nullToEmpty(p.languageQualifications())) {
            String field = "languageQualifications[" + i++ + "]";
            if (!seen.add(q.test())) {
                v.add(new AccountException.Violation(field, "Each test can be listed once."));
                continue;
            }
            BigDecimal[] range = RANGES.get(q.test());
            if (range != null && (q.overall().compareTo(range[0]) < 0 || q.overall().compareTo(range[1]) > 0)) {
                v.add(new AccountException.Violation(field, q.test().name() + " scores run from " + range[0].toPlainString() + " to "
                        + range[1].toPlainString() + "."));
            }
            if (q.test() == LanguageTest.OTHER && blank(q.testName()) == null) {
                v.add(new AccountException.Violation(field, "Name the test."));
            }
        }
        for (String c : nullToEmpty(p.preferredDestinations())) {
            if (!COUNTRIES.contains(c)) {
                v.add(new AccountException.Violation("preferredDestinations", "Unknown country code " + c + "."));
            }
        }
        return v;
    }

    private static boolean validCurrency(String code) {
        try {
            Currency.getInstance(code);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
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
