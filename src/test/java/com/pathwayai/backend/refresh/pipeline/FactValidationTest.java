package com.pathwayai.backend.refresh.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.pathwayai.backend.refresh.extract.ExtractedFact;
import com.pathwayai.backend.refresh.extract.ExtractedFact.FactType;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** NORMALIZE and VALIDATE: synthetic facts only. */
class FactValidationTest {

    static final LocalDate TODAY = LocalDate.of(2026, 6, 1);

    static ExtractedFact tuition(String... overrides) {
        Map<String, String> v = new HashMap<>(Map.of("amount", "9000", "currency", "EUR", "billingPeriod", "PER_YEAR",
                "studentCategory", "INTERNATIONAL", "academicYear", "2026/2027"));
        for (int i = 0; i < overrides.length; i += 2) {
            if (overrides[i + 1] == null) {
                v.remove(overrides[i]);
            } else {
                v.put(overrides[i], overrides[i + 1]);
            }
        }
        return ExtractedFact.of(FactType.TUITION, "k", v);
    }

    static List<String> check(ExtractedFact f) {
        return FactValidator.validate(FactNormalizer.normalize(f), true, TODAY);
    }

    @Test
    void aWellFormedTuitionFactIsValid() {
        assertThat(check(tuition())).isEmpty();
    }

    @Test
    void normalisationUnifiesSpellingButNeverRepairsAmbiguity() {
        var n = FactNormalizer.normalize(tuition("amount", "9 000", "currency", " eur ", "studentCategory", "international"));
        assertThat(n.values()).containsEntry("amount", "9000").containsEntry("currency", "EUR").containsEntry("studentCategory", "INTERNATIONAL");
        // "20,000" could be twenty thousand or twenty: it is left alone and rejected, never guessed
        assertThat(FactNormalizer.normalize(tuition("amount", "20,000")).values()).containsEntry("amount", "20,000");
        assertThat(check(tuition("amount", "20,000"))).anyMatch(e -> e.contains("ambiguous"));
        assertThat(check(tuition("amount", "20.000,50"))).isNotEmpty();
    }

    @Test
    void rejectsBadAmountsCurrenciesUnitsAndCategories() {
        assertThat(check(tuition("amount", "-5"))).isNotEmpty();
        assertThat(check(tuition("amount", "free"))).isNotEmpty();
        assertThat(check(tuition("amount", "5000000"))).anyMatch(e -> e.contains("implausibly"));
        assertThat(check(tuition("currency", "EURO"))).anyMatch(e -> e.contains("ISO 4217"));
        assertThat(check(tuition("currency", "XXY"))).isNotEmpty();
        assertThat(check(tuition("billingPeriod", "MONTHLY"))).anyMatch(e -> e.contains("billingPeriod"));
        assertThat(check(tuition("billingPeriod", null))).isNotEmpty();
        assertThat(check(tuition("studentCategory", "EVERYONE"))).anyMatch(e -> e.contains("studentCategory"));
    }

    @Test
    void academicYearsMustBeConsecutiveAndRangesOrdered() {
        assertThat(check(tuition("academicYear", "2026/2028"))).anyMatch(e -> e.contains("consecutive"));
        assertThat(check(tuition("academicYear", "2026"))).isNotEmpty();
        assertThat(check(tuition("academicYear", "26/27"))).isNotEmpty();
        assertThat(check(tuition("academicYear", null, "effectiveFrom", "2025-08-01"))).isEmpty(); // a range instead of a year
        assertThat(check(tuition("academicYear", null, "effectiveFrom", "2025-08-01", "effectiveTo", "2024-01-01")))
                .anyMatch(e -> e.contains("after"));
        assertThat(check(tuition("academicYear", null, "effectiveFrom", "01/08/2025"))).anyMatch(e -> e.contains("ISO date"));
    }

    @Test
    void aFactForAPageNotBoundToOneProgramCannotBeAttributed() {
        assertThat(FactValidator.validate(FactNormalizer.normalize(tuition()), false, TODAY)).anyMatch(e -> e.contains("not bound"));
        assertThat(FactValidator.validate(ExtractedFact.of(FactType.SOURCE_CONTENT, "", Map.of("contentHash", "x")), false, TODAY)).isEmpty();
    }

    @Test
    void validatesDeadlines() {
        var ok = ExtractedFact.of(FactType.DEADLINE, "k", Map.of("label", "Autumn 2027", "applicationDeadline", "2027-01-15",
                "startDate", "2027-08-30", "applicantCategory", "international"));
        assertThat(check(ok)).isEmpty();
        assertThat(check(ExtractedFact.of(FactType.DEADLINE, "k", Map.of("label", "x", "applicationDeadline", "15 January 2027")))).anyMatch(e -> e.contains("ISO date"));
        assertThat(check(ExtractedFact.of(FactType.DEADLINE, "k", Map.of("label", "x")))).anyMatch(e -> e.contains("required"));
        assertThat(check(ExtractedFact.of(FactType.DEADLINE, "k", Map.of("label", "x", "applicationDeadline", "1999-01-01")))).anyMatch(e -> e.contains("plausible"));
        assertThat(check(ExtractedFact.of(FactType.DEADLINE, "k", Map.of("label", "x", "applicationDeadline", "2031-01-01")))).anyMatch(e -> e.contains("plausible"));
        assertThat(check(ExtractedFact.of(FactType.DEADLINE, "k", Map.of("label", "x", "applicationDeadline", "2027-09-01", "startDate", "2027-08-30"))))
                .anyMatch(e -> e.contains("after the study start"));
        assertThat(check(ExtractedFact.of(FactType.DEADLINE, "k", Map.of("label", " ", "applicationDeadline", "2027-01-01")))).isNotEmpty();
    }

    @Test
    void validatesDurationAndLanguages() {
        assertThat(check(ExtractedFact.of(FactType.DURATION_MONTHS, "", Map.of("months", "24")))).isEmpty();
        assertThat(check(ExtractedFact.of(FactType.DURATION_MONTHS, "", Map.of("months", "0")))).isNotEmpty();
        assertThat(check(ExtractedFact.of(FactType.DURATION_MONTHS, "", Map.of("months", "2.5")))).isNotEmpty();
        assertThat(check(ExtractedFact.of(FactType.DURATION_MONTHS, "", Map.of("months", "999")))).isNotEmpty();
        assertThat(check(ExtractedFact.of(FactType.LANGUAGES, "", Map.of("codes", "EN, fi")))).isEmpty(); // normalised to en,fi
        assertThat(check(ExtractedFact.of(FactType.LANGUAGES, "", Map.of("codes", "english")))).isNotEmpty();
        assertThat(check(ExtractedFact.of(FactType.LANGUAGES, "", Map.of("codes", "")))).isNotEmpty();
    }

    @Test
    void theCanonicalValueIsOrderIndependentAndReadable() {
        var a = ExtractedFact.of(FactType.TUITION, "k", Map.of("b", "2", "a", "1"));
        var b = ExtractedFact.of(FactType.TUITION, "k", Map.of("a", "1", "b", "2"));
        assertThat(a.canonicalValue()).isEqualTo(b.canonicalValue());
        assertThat(ExtractedFact.parseValue(a.canonicalValue())).containsEntry("a", "1").containsEntry("b", "2");
    }
}
