package com.pathwayai.backend.common.discovery;

import com.pathwayai.backend.program.entity.DegreeProgram;
import com.pathwayai.backend.program.entity.ProgramFreshness;
import com.pathwayai.backend.university.entity.University;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/**
 * What the public may discover. When enabled (the default for the running application) a program is listed only if
 * it is ACTIVE (not discontinued by its official source), VERIFIED, belongs to a VERIFIED university and has a working
 * fact-extracting monitor (ADAPTER or JSON_LD, never a page-change monitor) read successfully within
 * {@code app.discovery.max-source-age-days}. A program whose extractor stopped, failed or went stale simply drops out
 * of discovery instead of being shown with stale facts; the data itself is never deleted, and it returns the moment a
 * refresh succeeds. Universities are listed unless they are explicitly UNVERIFIED.
 *
 * <p>Every specification returned is a real predicate, never null (Spring Data 4).
 */
@Component
public class DiscoveryPolicy {

    private final boolean restrict;
    private final Duration maxSourceAge;
    private final Clock clock;

    @Autowired
    public DiscoveryPolicy(@Value("${app.discovery.restrict-to-verified-fresh:true}") boolean restrict,
                           @Value("${app.discovery.max-source-age-days:45}") int maxSourceAgeDays, Clock clock) {
        this.restrict = restrict;
        this.maxSourceAge = Duration.ofDays(maxSourceAgeDays);
        this.clock = clock;
    }

    /** No restriction at all: for unit tests that construct a service by hand. */
    public static DiscoveryPolicy openToAll() {
        return new DiscoveryPolicy(false, 45, Clock.systemUTC());
    }

    public boolean restricts() {
        return restrict;
    }

    public int maxSourceAgeDays() {
        return (int) maxSourceAge.toDays();
    }

    /** A source read successfully before this instant no longer counts as fresh. */
    public Instant cutoff() {
        return clock.instant().minus(maxSourceAge);
    }

    public Instant now() {
        return clock.instant();
    }

    public Specification<DegreeProgram> programs() {
        if (!restrict) {
            return (root, query, cb) -> cb.conjunction();
        }
        Instant cutoff = clock.instant().minus(maxSourceAge);
        return (root, query, cb) -> {
            // explicit publication eligibility: a working, fresh FACT-EXTRACTING monitor (official API connector or permitted
            // structured-data extractor). A page-change (CONTENT_HASH) monitor never makes a program eligible.
            Subquery<Long> recent = query.subquery(Long.class);
            Root<ProgramFreshness> f = recent.from(ProgramFreshness.class);
            recent.select(f.get("sourceId")).where(cb.equal(f.get("programId"), root.get("id")),
                    f.<String>get("extractionMethod").in("ADAPTER", "JSON_LD"),
                    cb.equal(f.get("status"), "ACTIVE"),
                    cb.greaterThanOrEqualTo(f.<Instant>get("lastSuccessAt"), cutoff));
            // a monitor that extracts facts (not a mere page-change alarm) that is failing, unsupported or stale means the
            // facts it maintains cannot be called current, even when some other page of the program was read recently
            Subquery<Long> brokenExtractor = query.subquery(Long.class);
            Root<ProgramFreshness> b = brokenExtractor.from(ProgramFreshness.class);
            brokenExtractor.select(b.get("sourceId")).where(cb.equal(b.get("programId"), root.get("id")),
                    b.<String>get("extractionMethod").in("ADAPTER", "JSON_LD"),
                    cb.or(b.<String>get("status").in("FAILING", "UNSUPPORTED"), cb.isNull(b.get("lastSuccessAt")),
                            cb.lessThan(b.<Instant>get("lastSuccessAt"), cutoff)));
            return cb.and(
                    cb.equal(root.get("lifecycleStatus"), "ACTIVE"),
                    cb.equal(root.get("verificationStatus"), com.pathwayai.backend.program.entity.VerificationStatus.VERIFIED),
                    cb.equal(root.get("university").get("verificationStatus"), "VERIFIED"),
                    cb.exists(recent), cb.not(cb.exists(brokenExtractor)));
        };
    }

    public Specification<University> universities() {
        if (!restrict) {
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> cb.notEqual(root.get("verificationStatus"), "UNVERIFIED");
    }
}
