package com.pathwayai.backend.provider.studyinfo;

import com.pathwayai.backend.refresh.extract.ExtractedFact;
import com.pathwayai.backend.refresh.extract.ExtractionAdapter;
import com.pathwayai.backend.refresh.extract.PageCanonicalizer.CanonicalPage;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads program facts from the Studyinfo official API (see {@link StudyinfoParser}). It is the "official API" tier of
 * the adapter policy: structured, explicit, deterministic. Bump {@link #version()} whenever the parser or the field
 * mapping could change what it returns, so the monitor re-baselines instead of reporting a phantom change.
 */
@Component
public class StudyinfoAdapter implements ExtractionAdapter {

    public static final String NAME = "studyinfo-external";
    public static final String VERSION = "4";

    private static final Logger log = LoggerFactory.getLogger(StudyinfoAdapter.class);

    private final FieldTaxonomy taxonomy;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.pathwayai.backend.provider.universitypages.CuratedSources curated;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private org.springframework.jdbc.core.simple.JdbcClient jdbc;

    public StudyinfoAdapter(FieldTaxonomy taxonomy) {
        this.taxonomy = taxonomy;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public java.util.Optional<String> discontinuation(CanonicalPage page) {
        return StudyinfoParser.discontinuation(page.text());
    }

    @Override
    public List<ExtractedFact> extract(CanonicalPage page) {
        String oid=tools.jackson.databind.json.JsonMapper.builder().build().readTree(page.text()).path("oid").asText();
        var candidate=curated==null?null:curated.byEducationOid(oid);
        String adoptedFrom=null;
        if(candidate!=null) {
            // an implementation adopted in an earlier cycle is the reviewed one now
            String current=currentImplementation(oid);
            if(current!=null && !current.equals(candidate.implementationOid())) candidate=candidate.withImplementation(current);
            var next=com.pathwayai.backend.provider.universitypages.CuratedSources.successor(candidate,page.text());
            if(next.isPresent()) {
                adoptedFrom=candidate.implementationOid();
                candidate=candidate.withImplementation(next.get());
                log.info("Studyinfo education {}: reviewed implementation {} is no longer published; verifying successor {}",
                        oid,adoptedFrom,next.get());
            }
            com.pathwayai.backend.provider.universitypages.CuratedSources.validateStudyinfo(candidate,page.text());
        }
        StudyinfoParser.Parsed parsed = candidate==null ? StudyinfoParser.parse(page.text(),taxonomy)
                : StudyinfoParser.parse(page.text(),taxonomy,candidate.implementationOid(),candidate.combined(),candidate.providerOid(),candidate.titleCodes(),candidate.reviewedField());
        if(candidate!=null && (parsed.program()==null || !candidate.entryLevel().equals(parsed.program().degreeLevel())
                || !parsed.program().languages().contains("en")))
            throw new IllegalStateException("Reviewed offering has an unsupported degree or teaching language change");
        if (!parsed.issues().isEmpty()) {
            log.info("Studyinfo record left some facts out: {}", parsed.issues());
        }
        if (parsed.program() == null) {
            return List.of();
        }
        List<ExtractedFact> facts = new java.util.ArrayList<>(StudyinfoParser.toFacts(parsed.program()));
        if (adoptedFrom != null) {
            // passed every reviewed check above (level, titles, eligibility, English): adopt it as an audited fact
            facts.add(ExtractedFact.of(ExtractedFact.FactType.IMPLEMENTATION, "", java.util.Map.of("educationOid", oid,
                    "implementationOid", candidate.implementationOid(), "previous", adoptedFrom)));
        }
        return facts;
    }

    private String currentImplementation(String educationOid) {
        if (jdbc == null) return null;
        return jdbc.sql("""
                select m.implementation_oid from catalog_membership m join catalog_release r on r.id = m.release_id
                where r.enabled and m.education_oid = :oid""").param("oid", educationOid).query(String.class).optional().orElse(null);
    }
}
