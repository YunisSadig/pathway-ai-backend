package com.pathwayai.backend.refresh.pipeline;

import com.pathwayai.backend.refresh.extract.ExtractedFact.FactType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The only place the pipeline reads or writes published program facts. Reads support the COMPARE step; writes are
 * strictly additive for dated facts: a tuition fee or deadline for a new period is a NEW row, so older academic
 * years and application cycles are preserved, and a row for a period that already exists is never overwritten.
 */
@Component
public class ProgramFacts {

    public enum Kind { NEW, UNCHANGED, CONFLICT }

    /** {@code existing} is the published value that was compared against (null for NEW). */
    public record Comparison(Kind kind, String existing) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    public ProgramFacts(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Comparison compare(long programId, FactType type, Map<String, String> v) {
        return switch (type) {
            case DURATION_MONTHS -> {
                Optional<Integer> current = jdbc.sql("select duration_months from degree_program where id = :p")
                        .param("p", programId).query(Integer.class).optional();
                if (current.isEmpty()) {
                    yield new Comparison(Kind.NEW, null);
                }
                yield current.get().toString().equals(v.get("months")) ? new Comparison(Kind.UNCHANGED, current.get().toString())
                        : new Comparison(Kind.CONFLICT, current.get().toString());
            }
            case LANGUAGES -> {
                String existing = String.join(",", jdbc.sql("select language_code from program_language where program_id = :p order by 1")
                        .param("p", programId).query(String.class).list());
                yield existing.isEmpty() ? new Comparison(Kind.NEW, null)
                        : sortedCodes(v.get("codes")).equals(existing) ? new Comparison(Kind.UNCHANGED, existing)
                        : new Comparison(Kind.CONFLICT, existing);
            }
            case TUITION -> compareTuition(programId, v);
            case DEADLINE -> compareDeadline(programId, v);
            case PROGRAM_NAME -> scalar(programId, "select name from degree_program where id = :p", v.get("name"));
            case DEGREE_LEVEL -> scalar(programId, "select degree_level from degree_program where id = :p", v.get("level"));
            case ACADEMIC_FIELD -> scalar(programId, FIELD_SLUG_SQL, v.get("field"));
            case CAMPUS_CITY -> scalar(programId, "select campus_city from degree_program where id = :p", v.get("city"));
            case REQUIREMENT, SOURCE_CONTENT -> new Comparison(Kind.NEW, null);
        };
    }

    private static final String FIELD_SLUG_SQL = "select af.slug from degree_program p left join academic_field af"
            + " on af.id = p.academic_field_id where p.id = :p";

    /** NEW when the column is empty, UNCHANGED when it already holds exactly this value, otherwise CONFLICT. */
    private Comparison scalar(long programId, String sql, String incoming) {
        String current = currentOf(programId, sql);
        if (current == null) {
            return new Comparison(Kind.NEW, null);
        }
        return current.equals(incoming) ? new Comparison(Kind.UNCHANGED, current) : new Comparison(Kind.CONFLICT, current);
    }

    private Comparison compareTuition(long programId, Map<String, String> v) {
        String year = blankToNull(v.get("academicYear"));
        LocalDate from = date(v.get("effectiveFrom"));
        LocalDate to = date(v.get("effectiveTo"));
        List<Map<String, Object>> rows;
        if (year != null) {
            rows = jdbc.sql("""
                    select amount, currency from tuition_fee where program_id = :p and student_category = :c
                      and billing_period = :b and academic_year = :y""")
                    .param("p", programId).param("c", v.get("studentCategory")).param("b", v.get("billingPeriod"))
                    .param("y", year).query().listOfRows();
        } else {
            rows = jdbc.sql("""
                    select amount, currency from tuition_fee where program_id = :p and student_category = :c
                      and billing_period = :b and academic_year is null
                      and effective_from is not distinct from cast(:f as date)
                      and effective_to is not distinct from cast(:t as date)""")
                    .param("p", programId).param("c", v.get("studentCategory")).param("b", v.get("billingPeriod"))
                    .param("f", from).param("t", to).query().listOfRows();
        }
        if (rows.isEmpty()) {
            return new Comparison(Kind.NEW, null);
        }
        BigDecimal amount = (BigDecimal) rows.get(0).get("amount");
        String currency = String.valueOf(rows.get(0).get("currency")).trim();
        String existing = amount.stripTrailingZeros().toPlainString() + " " + currency;
        boolean same = amount.compareTo(new BigDecimal(v.get("amount"))) == 0 && currency.equals(v.get("currency"));
        return new Comparison(same ? Kind.UNCHANGED : Kind.CONFLICT, existing);
    }

    private Comparison compareDeadline(long programId, Map<String, String> v) {
        List<LocalDate> rows = jdbc.sql("""
                select application_deadline from program_intake where program_id = :p and label = :l
                  and coalesce(academic_year, '') = :y and coalesce(applicant_category, '') = :c""")
                .param("p", programId).param("l", v.get("label")).param("y", v.getOrDefault("academicYear", ""))
                .param("c", v.getOrDefault("applicantCategory", "")).query(LocalDate.class).list();
        if (rows.isEmpty()) {
            return new Comparison(Kind.NEW, null);
        }
        LocalDate existing = rows.get(0);
        return existing != null && existing.equals(date(v.get("applicationDeadline")))
                ? new Comparison(Kind.UNCHANGED, String.valueOf(existing)) : new Comparison(Kind.CONFLICT, String.valueOf(existing));
    }

    /** Publishes a staged fact. Only called for NEW facts (or an approved fill); never overwrites a dated row. */
    public void apply(long programId, long sourceId, FactType type, Map<String, String> v) {
        apply(programId, sourceId, type, v, clock.instant(), null);
    }

    /**
     * Publishes a fact, archives the value it replaces and records which source stated the new one. A fact that is
     * already published is never passed here (the pipeline compares first), so unchanged data is never rewritten.
     */
    public void apply(long programId, long sourceId, FactType type, Map<String, String> v, Instant now, String parserVersion) {
        switch (type) {
            case PROGRAM_NAME -> {
                archive(programId, "name", currentOf(programId, "select name from degree_program where id = :p"), v.get("name"), sourceId, now);
                jdbc.sql("update degree_program set name = :n, updated_at = now() where id = :p")
                        .param("n", v.get("name")).param("p", programId).update();
                provenance(programId, "name", sourceId, now, parserVersion);
            }
            case DEGREE_LEVEL -> {
                archive(programId, "degree_level", currentOf(programId, "select degree_level from degree_program where id = :p"), v.get("level"), sourceId, now);
                jdbc.sql("update degree_program set degree_level = :l, updated_at = now() where id = :p")
                        .param("l", v.get("level")).param("p", programId).update();
                provenance(programId, "degree_level", sourceId, now, parserVersion);
            }
            case ACADEMIC_FIELD -> {
                archive(programId, "academic_field", currentOf(programId, FIELD_SLUG_SQL), v.get("field"), sourceId, now);
                int rows = jdbc.sql("""
                        update degree_program set academic_field_id = f.id, field_of_study = f.name, updated_at = now()
                        from academic_field f where f.slug = :s and degree_program.id = :p""")
                        .param("s", v.get("field")).param("p", programId).update();
                if (rows != 1) {
                    throw new IllegalStateException("unknown academic field slug: " + v.get("field"));
                }
                provenance(programId, "academic_field", sourceId, now, parserVersion);
            }
            case CAMPUS_CITY -> {
                archive(programId, "campus_city", currentOf(programId, "select campus_city from degree_program where id = :p"), v.get("city"), sourceId, now);
                jdbc.sql("update degree_program set campus_city = :c, updated_at = now() where id = :p")
                        .param("c", v.get("city")).param("p", programId).update();
                provenance(programId, "campus_city", sourceId, now, parserVersion);
            }
            case DURATION_MONTHS -> {
                archive(programId, "duration_months", currentOf(programId, "select duration_months from degree_program where id = :p"), v.get("months"), sourceId, now);
                jdbc.sql("update degree_program set duration_months = :m, updated_at = now() where id = :p")
                        .param("m", Integer.parseInt(v.get("months"))).param("p", programId).update();
                provenance(programId, "duration_months", sourceId, now, parserVersion);
            }
            case LANGUAGES -> {
                archive(programId, "languages", currentOf(programId, "select string_agg(language_code, ',' order by language_code) from program_language where program_id = :p"),
                        sortedCodes(v.get("codes")), sourceId, now);
                jdbc.sql("delete from program_language where program_id = :p").param("p", programId).update();
                for (String code : v.get("codes").split(",")) {
                    jdbc.sql("insert into program_language (program_id, language_code) values (:p, :c) on conflict do nothing")
                            .param("p", programId).param("c", code).update();
                }
                provenance(programId, "languages", sourceId, now, parserVersion);
            }
            case TUITION -> jdbc.sql("""
                    insert into tuition_fee (program_id, source_id, academic_year, student_category, amount, currency,
                                             billing_period, effective_from, effective_to, scope_note)
                    values (:p, :s, :y, :c, :a, :cur, :b, :f, :t, :n)""")
                    .param("p", programId).param("s", sourceId).param("y", blankToNull(v.get("academicYear")))
                    .param("c", v.get("studentCategory")).param("a", new BigDecimal(v.get("amount")))
                    .param("cur", v.get("currency")).param("b", v.get("billingPeriod"))
                    .param("f", date(v.get("effectiveFrom"))).param("t", date(v.get("effectiveTo")))
                    .param("n", truncate(blankToNull(v.get("scopeNote")), 300)).update();
            case DEADLINE -> jdbc.sql("""
                    insert into program_intake (program_id, source_id, label, academic_year, start_date,
                                                application_deadline, applicant_category)
                    values (:p, :s, :l, :y, :st, :d, :c)""")
                    .param("p", programId).param("s", sourceId).param("l", v.get("label"))
                    .param("y", blankToNull(v.get("academicYear"))).param("st", date(v.get("startDate")))
                    .param("d", date(v.get("applicationDeadline"))).param("c", blankToNull(v.get("applicantCategory"))).update();
            case REQUIREMENT, SOURCE_CONTENT -> throw new IllegalStateException(
                    type + " cannot be published automatically: use the reference-data import after review");
        }
        // provenance: the page that supports the fact is linked to the program
        jdbc.sql("insert into program_source (program_id, source_id) values (:p, :s) on conflict do nothing")
                .param("p", programId).param("s", sourceId).update();
    }

    private String currentOf(long programId, String sql) {
        return jdbc.sql(sql).param("p", programId).query((rs, i) -> rs.getString(1)).optional().orElse(null);
    }

    /** Keeps the value that is about to be replaced. The provider does not say when it stopped being valid. */
    private void archive(long programId, String field, String oldValue, String newValue, long sourceId, Instant now) {
        if (oldValue == null || oldValue.equals(newValue)) {
            return;
        }
        jdbc.sql("""
                insert into program_fact_history (program_id, field_name, value, recorded_source_id, superseded_at,
                                                  superseded_by_source, note)
                values (:p, :f, :old, (select source_id from program_field_provenance where program_id = :p and field_name = :f),
                        :now, :s, 'replaced when the new value was published here; the source does not state when the old value stopped being valid')""")
                .param("p", programId).param("f", field).param("old", oldValue).param("now", java.sql.Timestamp.from(now))
                .param("s", sourceId).update();
    }

    /** Records that a source stated a field that was set at creation, so no apply() ran for it. */
    public void recordProvenance(long programId, String field, long sourceId, Instant now, String parserVersion) {
        provenance(programId, field, sourceId, now, parserVersion);
    }

    /** Records which source stated a field, by which mechanism, and when it was verified. */
    private void provenance(long programId, String field, long sourceId, Instant now, String parserVersion) {
        jdbc.sql("""
                insert into program_field_provenance (program_id, field_name, source_id, mechanism, provider_ref, observed_at,
                                                      verified_on, parser_version)
                values (:p, :f, :s,
                        coalesce((select case when m.extraction_method = 'ADAPTER' and m.adapter_name like 'studyinfo%' then 'OFFICIAL_API'
                                              when m.extraction_method = 'JSON_LD' then 'STRUCTURED_DATA'
                                              else 'REVIEWED_PAGE' end
                                  from source_monitor m where m.source_id = :s), 'REVIEWED_PAGE'),
                        (select e.value from program_external_id e where e.program_id = :p and e.scheme = 'STUDYINFO_KOULUTUS_OID'),
                        :now, :on, :pv)
                on conflict (program_id, field_name) do update set source_id = excluded.source_id,
                    mechanism = excluded.mechanism, provider_ref = excluded.provider_ref, observed_at = excluded.observed_at,
                    verified_on = excluded.verified_on, parser_version = excluded.parser_version""")
                .param("p", programId).param("f", field).param("s", sourceId).param("now", java.sql.Timestamp.from(now))
                .param("on", LocalDate.ofInstant(now, ZoneOffset.UTC)).param("pv", parserVersion).update();
    }

    private static String sortedCodes(String codes) {
        return String.join(",", java.util.Arrays.stream(codes.split(",")).sorted().toList());
    }

    private static LocalDate date(String s) {
        return s == null || s.isEmpty() ? null : LocalDate.parse(s);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
