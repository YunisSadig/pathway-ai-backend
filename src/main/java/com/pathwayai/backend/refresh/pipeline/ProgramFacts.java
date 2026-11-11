package com.pathwayai.backend.refresh.pipeline;

import com.pathwayai.backend.refresh.extract.ExtractedFact.FactType;
import java.math.BigDecimal;
import java.time.LocalDate;
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

    public ProgramFacts(JdbcClient jdbc) {
        this.jdbc = jdbc;
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
            case REQUIREMENT, SOURCE_CONTENT -> new Comparison(Kind.NEW, null);
        };
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
        switch (type) {
            case DURATION_MONTHS -> jdbc.sql("update degree_program set duration_months = :m, updated_at = now() where id = :p")
                    .param("m", Integer.parseInt(v.get("months"))).param("p", programId).update();
            case LANGUAGES -> {
                jdbc.sql("delete from program_language where program_id = :p").param("p", programId).update();
                for (String code : v.get("codes").split(",")) {
                    jdbc.sql("insert into program_language (program_id, language_code) values (:p, :c) on conflict do nothing")
                            .param("p", programId).param("c", code).update();
                }
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
