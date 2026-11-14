package com.pathwayai.backend.program.repository;

import com.pathwayai.backend.program.entity.DegreeLevel;
import com.pathwayai.backend.program.entity.DegreeProgram;
import com.pathwayai.backend.tuition.entity.BillingPeriod;
import com.pathwayai.backend.tuition.entity.StudentCategory;
import com.pathwayai.backend.tuition.entity.TuitionFee;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.math.BigDecimal;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

/**
 * Composable filters for program discovery. Every "no filter" case returns an explicit match-all,
 * because Spring Data 4 rejects null specifications in and()/or().
 */
public final class ProgramSpecifications {

    private static final char ESCAPE = '\\';

    private ProgramSpecifications() {
    }

    /**
     * Loads university, country and field in the page query (skipped for the count query) so a list
     * page never triggers one extra query per row.
     */
    public static Specification<DegreeProgram> fetchAssociations() {
        return (root, query, cb) -> {
            if (query != null && !Long.class.equals(query.getResultType())
                    && !long.class.equals(query.getResultType())) {
                root.fetch("university", JoinType.INNER).fetch("country", JoinType.INNER);
                root.fetch("academicField", JoinType.LEFT);
            }
            return cb.conjunction();
        };
    }

    /** Case-insensitive "contains" on the program name; LIKE wildcards in user input are literal. */
    public static Specification<DegreeProgram> nameContains(String text) {
        if (blank(text)) {
            return matchAll();
        }
        String pattern = "%" + escapeLike(text.trim().toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.like(cb.lower(root.get("name")), pattern, ESCAPE);
    }

    public static Specification<DegreeProgram> atLevel(DegreeLevel level) {
        if (level == null) {
            return matchAll();
        }
        return (root, query, cb) -> cb.equal(root.get("degreeLevel"), level);
    }

    /** Matches the field with this slug and its direct children (the taxonomy has two levels in the API). */
    public static Specification<DegreeProgram> inField(String fieldSlug) {
        if (blank(fieldSlug)) {
            return matchAll();
        }
        String slug = fieldSlug.trim().toLowerCase(Locale.ROOT);
        return (root, query, cb) -> {
            var field = root.join("academicField", JoinType.INNER);
            return cb.or(cb.equal(field.get("slug"), slug),
                    cb.equal(field.join("parent", JoinType.LEFT).get("slug"), slug));
        };
    }

    public static Specification<DegreeProgram> inCountry(String iso2Code) {
        if (blank(iso2Code)) {
            return matchAll();
        }
        String code = iso2Code.trim().toUpperCase(Locale.ROOT);
        return (root, query, cb) -> cb.equal(root.get("university").get("country").get("iso2Code"), code);
    }

    public static Specification<DegreeProgram> atUniversity(String universitySlug) {
        if (blank(universitySlug)) {
            return matchAll();
        }
        String slug = universitySlug.trim().toLowerCase(Locale.ROOT);
        return (root, query, cb) -> cb.equal(root.get("university").get("slug"), slug);
    }

    /** Matches programs taught in the given language code (a program may have several). */
    public static Specification<DegreeProgram> taughtIn(String languageCode) {
        if (blank(languageCode)) {
            return matchAll();
        }
        String code = languageCode.trim().toLowerCase(Locale.ROOT);
        return (root, query, cb) -> cb.isMember(code, root.<java.util.Set<String>>get("teachingLanguageCodes"));
    }

    /**
     * Programs with a known yearly tuition of at most {@code max} in {@code currency}. Programs whose
     * tuition is unknown are deliberately excluded: unknown is not "free". Amounts in different currencies
     * or billing periods are never compared. {@code category} may be null (any category).
     */
    public static Specification<DegreeProgram> tuitionAtMost(BigDecimal max, String currency,
                                                              StudentCategory category) {
        if (max == null) {
            return matchAll();
        }
        String code = currency.trim().toUpperCase(Locale.ROOT);
        return (root, query, cb) -> {
            Subquery<Long> sub = query.subquery(Long.class);
            Root<TuitionFee> fee = sub.from(TuitionFee.class);
            var where = cb.and(
                    cb.equal(fee.get("program"), root),
                    cb.equal(fee.get("currency"), code),
                    cb.equal(fee.get("billingPeriod"), BillingPeriod.PER_YEAR),
                    cb.lessThanOrEqualTo(fee.<BigDecimal>get("amount"), max));
            if (category != null) {
                where = cb.and(where, cb.equal(fee.get("studentCategory"), category));
            }
            sub.select(fee.<Long>get("id")).where(where);
            return cb.exists(sub);
        };
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static Specification<DegreeProgram> matchAll() {
        return (root, query, cb) -> cb.conjunction();
    }

    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
