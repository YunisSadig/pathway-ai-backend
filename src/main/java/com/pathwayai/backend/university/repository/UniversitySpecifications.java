package com.pathwayai.backend.university.repository;

import com.pathwayai.backend.university.entity.University;
import jakarta.persistence.criteria.JoinType;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

public final class UniversitySpecifications {

    private static final char ESCAPE = '\\';

    private UniversitySpecifications() {
    }

    /** Loads the country in the same query for page content (skipped for the count query). */
    public static Specification<University> fetchCountry() {
        return (root, query, cb) -> {
            if (query != null && !Long.class.equals(query.getResultType())
                    && !long.class.equals(query.getResultType())) {
                root.fetch("country", JoinType.INNER);
            }
            return cb.conjunction();
        };
    }

    /** Case-insensitive "contains" match on the name; LIKE wildcards in user input are escaped. */
    public static Specification<University> nameContains(String text) {
        if (text == null || text.isBlank()) {
            return matchAll();
        }
        String pattern = "%" + escapeLike(text.trim().toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.like(cb.lower(root.get("name")), pattern, ESCAPE);
    }

    public static Specification<University> inCountry(String iso2Code) {
        if (iso2Code == null || iso2Code.isBlank()) {
            return matchAll();
        }
        String code = iso2Code.trim().toUpperCase(Locale.ROOT);
        return (root, query, cb) -> cb.equal(root.get("country").get("iso2Code"), code);
    }

    /** Spring Data 4 rejects null specifications in and()/or(), so "no filter" is an explicit match-all. */
    private static Specification<University> matchAll() {
        return (root, query, cb) -> cb.conjunction();
    }

    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
