package com.pathwayai.backend.program.importer;

import com.pathwayai.backend.program.entity.VerificationStatus;
import com.pathwayai.backend.program.importer.ReferenceData.ProgramData;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure integrity checks for a reference-data file (no database). Used by the importer before it writes
 * anything, and by a unit test that guards the shipped data file.
 */
public final class ReferenceDataValidator {

    private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    private static final Pattern HTTP_URL = Pattern.compile("https?://\\S+");
    private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2,3}");
    private static final Pattern ACADEMIC_YEAR = Pattern.compile("(\\d{4})/(\\d{4})");
    private static final Pattern IDENTIFIER =
            Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*--[a-z0-9]+(-[a-z0-9]+)*");

    private ReferenceDataValidator() {
    }

    /** Returns every problem found; an empty list means the file is consistent. */
    public static List<String> validate(ReferenceData data) {
        List<String> errors = new ArrayList<>();
        if (data.schemaVersion() != ReferenceData.SUPPORTED_SCHEMA_VERSION) {
            errors.add("schemaVersion must be " + ReferenceData.SUPPORTED_SCHEMA_VERSION);
        }
        Set<String> countries = new HashSet<>();
        for (var c : orEmpty(data.countries())) {
            if (c.iso2() == null || !c.iso2().matches("[A-Z]{2}")) errors.add("country iso2 invalid: " + c.iso2());
            if (blank(c.name())) errors.add("country name missing: " + c.iso2());
            countries.add(c.iso2());
        }
        Set<String> fields = new HashSet<>();
        for (var f : orEmpty(data.fields())) {
            if (!slug(f.slug())) errors.add("field slug invalid: " + f.slug());
            if (blank(f.name())) errors.add("field name missing: " + f.slug());
            if (!fields.add(f.slug())) errors.add("duplicate field slug: " + f.slug());
        }
        for (var f : orEmpty(data.fields())) {
            if (f.parent() != null && !fields.contains(f.parent())) {
                errors.add("field " + f.slug() + " has unknown parent " + f.parent());
            }
            if (Objects.equals(f.slug(), f.parent())) errors.add("field is its own parent: " + f.slug());
        }
        Set<String> sources = new HashSet<>();
        for (var s : orEmpty(data.sources())) {
            if (s.url() == null || !HTTP_URL.matcher(s.url()).matches()) errors.add("source url invalid: " + s.url());
            if (blank(s.title())) errors.add("source title missing: " + s.url());
            if (s.type() == null) errors.add("source type missing: " + s.url());
            if (s.retrievedOn() == null) errors.add("source retrievedOn missing: " + s.url());
            if (!sources.add(s.url())) errors.add("duplicate source url: " + s.url());
        }
        Set<String> universities = new HashSet<>();
        for (var u : orEmpty(data.universities())) {
            if (!slug(u.slug())) errors.add("university slug invalid: " + u.slug());
            if (!countries.contains(u.country())) errors.add("university " + u.slug() + " unknown country " + u.country());
            if (blank(u.name())) errors.add("university name missing: " + u.slug());
            if (u.websiteUrl() != null && !HTTP_URL.matcher(u.websiteUrl()).matches()) {
                errors.add("university website invalid: " + u.slug());
            }
            if (!universities.add(u.slug())) errors.add("duplicate university slug: " + u.slug());
        }
        Set<String> identifiers = new HashSet<>();
        for (ProgramData p : orEmpty(data.programs())) {
            validateProgram(p, universities, fields, sources, errors);
            if (!identifiers.add(p.university() + "--" + p.slug())) {
                errors.add("duplicate program: " + p.university() + "--" + p.slug());
            }
        }
        for (var r : data.retractions()) {
            if (r.type() == null) errors.add("retraction type missing");
            if (r.program() == null || !IDENTIFIER.matcher(r.program()).matches()) {
                errors.add("retraction program identifier invalid: " + r.program());
            }
            if (blank(r.reason())) errors.add("retraction of " + r.program() + " needs a reason");
            if (r.type() == ReferenceData.RetractionType.TUITION
                    && (r.academicYear() == null || r.studentCategory() == null)) {
                errors.add("tuition retraction of " + r.program() + " needs academicYear and studentCategory");
            }
            if (r.type() == ReferenceData.RetractionType.INTAKE && blank(r.label())) {
                errors.add("intake retraction of " + r.program() + " needs a label");
            }
        }
        return errors;
    }

    private static void validateProgram(ProgramData p, Set<String> universities, Set<String> fields,
                                        Set<String> sources, List<String> errors) {
        String id = p.university() + "--" + p.slug();
        if (!universities.contains(p.university())) errors.add(id + ": unknown university");
        if (!slug(p.slug())) errors.add(id + ": slug invalid");
        if (blank(p.name())) errors.add(id + ": name missing");
        if (p.degreeLevel() == null) errors.add(id + ": degreeLevel missing");
        if (p.field() != null && !fields.contains(p.field())) errors.add(id + ": unknown field " + p.field());
        if (p.durationMonths() != null && p.durationMonths() <= 0) errors.add(id + ": durationMonths must be > 0");
        for (String l : orEmpty(p.languages())) {
            if (!LANGUAGE.matcher(l).matches()) errors.add(id + ": language code invalid: " + l);
        }
        for (String url : new String[] {p.officialProgramUrl(), p.officialAdmissionsUrl(),
                p.officialApplicationUrl()}) {
            if (url != null && !HTTP_URL.matcher(url).matches()) errors.add(id + ": url invalid: " + url);
        }
        if (p.officialApplicationUrl() != null && blank(p.officialApplicationCycle())) {
            errors.add(id + ": an application URL requires officialApplicationCycle");
        }
        if (p.officialApplicationUrl() == null && p.officialApplicationCycle() != null) {
            errors.add(id + ": officialApplicationCycle without an application URL");
        }
        var v = p.verification();
        boolean verified = v != null && v.status() == VerificationStatus.VERIFIED;
        if (verified && v.verifiedOn() == null) errors.add(id + ": VERIFIED requires verifiedOn");
        if (p.officialApplicationUrl() != null && !verified) {
            errors.add(id + ": an application URL requires a VERIFIED program");
        }
        if (verified && orEmpty(p.sources()).isEmpty()) errors.add(id + ": VERIFIED requires at least one source");
        for (String s : orEmpty(p.sources())) {
            if (!sources.contains(s)) errors.add(id + ": unknown source " + s);
        }
        for (String url : new String[] {p.officialAdmissionsUrl(), p.officialApplicationUrl()}) {
            if (url != null && !orEmpty(p.sources()).contains(url)) {
                errors.add(id + ": " + url + " must be one of the program sources (a page that was actually read)");
            }
        }
        for (var i : orEmpty(p.intakes())) {
            if (blank(i.label())) errors.add(id + ": intake label missing");
            if (i.academicYear() != null && !ACADEMIC_YEAR.matcher(i.academicYear()).matches()) {
                errors.add(id + ": intake academicYear must look like 2026/2027: " + i.academicYear());
            }
            requireSource(id + " intake", i.source(), sources, errors);
        }
        for (var t : orEmpty(p.tuition())) {
            if (t.amount() == null || t.amount().signum() < 0) errors.add(id + ": tuition amount invalid");
            if (t.currency() == null || !t.currency().matches("[A-Z]{3}")) errors.add(id + ": tuition currency invalid");
            if (t.studentCategory() == null || t.billingPeriod() == null) {
                errors.add(id + ": tuition needs studentCategory and billingPeriod");
            }
            if (t.academicYear() != null) {
                var m = ACADEMIC_YEAR.matcher(t.academicYear());
                if (!m.matches() || Integer.parseInt(m.group(2)) != Integer.parseInt(m.group(1)) + 1) {
                    errors.add(id + ": tuition academicYear must look like 2026/2027: " + t.academicYear());
                }
                if (t.effectiveFrom() != null || t.effectiveTo() != null) {
                    errors.add(id + ": tuition has both academicYear and an effective range; state the one the source gives");
                }
            }
            if (t.effectiveFrom() != null && t.effectiveTo() != null && t.effectiveFrom().isAfter(t.effectiveTo())) {
                errors.add(id + ": tuition effectiveFrom is after effectiveTo");
            }
            requireSource(id + " tuition", t.source(), sources, errors);
        }
        for (var r : orEmpty(p.requirements())) {
            if (r.type() == null || blank(r.description()) || blank(r.applicationCycle())) {
                errors.add(id + ": requirement needs type, applicationCycle and description");
            }
            requireSource(id + " requirement", r.source(), sources, errors);
        }
    }

    private static void requireSource(String what, String url, Set<String> sources, List<String> errors) {
        if (url == null || !sources.contains(url)) errors.add(what + " must cite a listed source, got " + url);
    }

    private static boolean slug(String value) {
        return value != null && SLUG.matcher(value).matches();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
