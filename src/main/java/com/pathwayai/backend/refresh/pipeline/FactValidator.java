package com.pathwayai.backend.refresh.pipeline;

import com.pathwayai.backend.refresh.extract.ExtractedFact;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * VALIDATE step: types, units, currencies, applicant categories, academic years, dates and identity. Returns every
 * problem; an empty list means the fact is well-formed (not that it is correct: that is what review is for).
 */
public final class FactValidator {

    private static final Pattern ACADEMIC_YEAR = Pattern.compile("^(\\d{4})/(\\d{4})$");
    private static final Set<String> BILLING = Set.of("PER_YEAR", "PER_SEMESTER", "PER_PROGRAM");
    private static final Set<String> CATEGORY = Set.of("DOMESTIC", "INTERNATIONAL");
    private static final BigDecimal MAX_FEE = new BigDecimal("1000000");

    private FactValidator() {
    }

    public static List<String> validate(ExtractedFact fact, boolean boundToProgram, LocalDate today) {
        List<String> e = new ArrayList<>();
        Map<String, String> v = fact.values();
        boolean programFact = fact.type() != ExtractedFact.FactType.SOURCE_CONTENT;
        if (programFact && !boundToProgram) {
            e.add("the source is not bound to a single program, so a program fact cannot be attributed");
            return e;
        }
        switch (fact.type()) {
            case SOURCE_CONTENT -> {
            }
            case DURATION_MONTHS -> {
                Integer months = intValue(v.get("months"));
                if (months == null || months < 1 || months > 120) {
                    e.add("months must be a whole number from 1 to 120");
                }
            }
            case LANGUAGES -> {
                String codes = v.getOrDefault("codes", "");
                String[] parts = codes.isEmpty() ? new String[0] : codes.split(",");
                if (parts.length < 1 || parts.length > 8) {
                    e.add("one to eight language codes are required");
                }
                for (String c : parts) {
                    if (!c.matches("[a-z]{2,3}")) {
                        e.add("not an ISO 639 language code: " + c);
                    }
                }
            }
            case TUITION -> tuition(v, e);
            case DEADLINE -> deadline(v, e, today);
            case REQUIREMENT -> {
                if (blank(v.get("description")) || blank(v.get("type")) || blank(v.get("applicationCycle"))) {
                    e.add("a requirement needs a type, an application cycle and a description");
                }
            }
        }
        return e;
    }

    private static void tuition(Map<String, String> v, List<String> e) {
        String amount = v.get("amount");
        if (amount == null || !amount.matches("\\d+(\\.\\d{1,2})?")) {
            e.add("amount must be a plain decimal number (ambiguous separators are rejected, never guessed): " + amount);
        } else if (new BigDecimal(amount).compareTo(MAX_FEE) > 0) {
            e.add("amount is implausibly large");
        }
        try {
            Currency.getInstance(v.getOrDefault("currency", ""));
        } catch (IllegalArgumentException ex) {
            e.add("currency is not an ISO 4217 code: " + v.get("currency"));
        }
        if (v.get("billingPeriod") == null || !BILLING.contains(v.get("billingPeriod"))) {
            e.add("billingPeriod must be PER_YEAR, PER_SEMESTER or PER_PROGRAM");
        }
        if (v.get("studentCategory") == null || !CATEGORY.contains(v.get("studentCategory"))) {
            e.add("studentCategory must be DOMESTIC or INTERNATIONAL");
        }
        String year = v.get("academicYear");
        if (year != null && !year.isEmpty()) {
            Matcher m = ACADEMIC_YEAR.matcher(year);
            if (!m.matches() || Integer.parseInt(m.group(2)) != Integer.parseInt(m.group(1)) + 1) {
                e.add("academicYear must be two consecutive years such as 2026/2027: " + year);
            }
        }
        LocalDate from = date(v.get("effectiveFrom"), e, "effectiveFrom");
        LocalDate to = date(v.get("effectiveTo"), e, "effectiveTo");
        if (from != null && to != null && from.isAfter(to)) {
            e.add("effectiveFrom is after effectiveTo");
        }
    }

    private static void deadline(Map<String, String> v, List<String> e, LocalDate today) {
        if (blank(v.get("label")) || v.get("label").length() > 100) {
            e.add("label is required (max 100)");
        }
        LocalDate deadline = date(v.get("applicationDeadline"), e, "applicationDeadline");
        if (deadline == null && v.get("applicationDeadline") == null) {
            e.add("applicationDeadline is required");
        }
        if (deadline != null && (deadline.isBefore(LocalDate.of(2000, 1, 1)) || deadline.isAfter(today.plusYears(3)))) {
            e.add("applicationDeadline is outside a plausible range: " + deadline);
        }
        LocalDate start = date(v.get("startDate"), e, "startDate");
        if (deadline != null && start != null && deadline.isAfter(start)) {
            e.add("the application deadline is after the study start date");
        }
        String category = v.get("applicantCategory");
        if (category != null && !category.isEmpty() && !CATEGORY.contains(category)) {
            e.add("applicantCategory must be DOMESTIC or INTERNATIONAL");
        }
        String year = v.get("academicYear");
        if (year != null && !year.isEmpty() && !ACADEMIC_YEAR.matcher(year).matches()) {
            e.add("academicYear must look like 2026/2027: " + year);
        }
    }

    private static LocalDate date(String s, List<String> e, String field) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException ex) {
            e.add(field + " must be an ISO date (YYYY-MM-DD): " + s);
            return null;
        }
    }

    private static Integer intValue(String s) {
        try {
            return s == null ? null : Integer.valueOf(s);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
