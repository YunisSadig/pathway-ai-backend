package com.pathwayai.backend.provider.universitypages;

import static org.assertj.core.api.Assertions.*;
import com.pathwayai.backend.provider.studyinfo.FieldTaxonomy;
import com.pathwayai.backend.provider.studyinfo.StudyinfoParser;
import com.pathwayai.backend.refresh.extract.ExtractedFact;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A new academic year in a provider that publishes one implementation per intake (Helsinki, Tampere, Jyväskylä): the
 * recorded 2027 record is turned into a synthetic "next year" record (the 2027 implementation archived, a 2028 one
 * published with its own round). The successor is adopted only when it is unambiguous and identical in kind.
 */
class AdmissionCycleTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String NEXT = "1.2.246.562.17.00000000000099990001";
    final CuratedSources sources = new CuratedSources(new DefaultResourceLoader(), "classpath:reference-data/curated-finland.json");
    final CuratedSources.Candidate helsinki = sources.candidates().stream().filter(c -> c.key().equals("helsinki-computer-science")).findFirst().orElseThrow();

    /** The recorded record with its reviewed implementation archived and {@code copies} new 2028 implementations. */
    static String nextYear(CuratedSources.Candidate c, int copies, String provider, String language) throws Exception {
        ObjectNode root = (ObjectNode) JSON.readTree(CuratedSourceEvidenceTest.fixture(c.key() + ".json"));
        ArrayNode impls = (ArrayNode) root.get("toteutukset");
        ObjectNode reviewed = null;
        for (var t : impls) if (t.get("oid").asText().equals(c.implementationOid())) reviewed = (ObjectNode) t;
        ArrayNode targets = (ArrayNode) root.get("hakukohteet");
        ObjectNode target = null;
        for (var h : targets) if (h.get("toteutusOid").asText().equals(c.implementationOid())) target = (ObjectNode) h;
        ObjectNode round = null;
        for (var h : root.get("haut")) if (h.get("oid").asText().equals(target.get("hakuOid").asText())) round = (ObjectNode) h;
        for (int i = 0; i < copies; i++) {
            String oid = NEXT.substring(0, NEXT.length() - 1) + (i + 1);
            ObjectNode t = reviewed.deepCopy();
            t.put("oid", oid);
            ((ObjectNode) t.get("nimi")).put("en", "Master's Programme in Computer Science, Autumn 2028");
            if (provider != null) ((ObjectNode) t.get("tarjoajat").get(0)).put("oid", provider);
            if (language != null) ((ObjectNode) t.get("metadata").get("opetus").get("opetuskieli").get(0)).put("koodiUri", language);
            ObjectNode start = (ObjectNode) t.get("metadata").get("opetus").get("koulutuksenAlkamiskausi");
            if (start != null) start.put("koulutuksenAlkamisvuosi", "2028");
            impls.add(t);
            ObjectNode r = round.deepCopy();
            r.put("oid", "1.2.246.562.29.0000000000009999000" + (i + 1));
            ((ObjectNode) r.get("hakuajat").get(0)).put("alkaa", "2028-01-04T08:00").put("paattyy", "2028-01-18T15:00");
            ObjectNode rs = (ObjectNode) r.get("metadata").get("koulutuksenAlkamiskausi");
            if (rs != null) rs.put("koulutuksenAlkamisvuosi", "2028");
            ((ArrayNode) root.get("haut")).add(r);
            ObjectNode h = target.deepCopy();
            h.put("oid", "1.2.246.562.20.0000000000009999000" + (i + 1)).put("toteutusOid", oid).put("hakuOid", r.get("oid").asText());
            if (!h.path("kaytetaanHaunAikataulua").asBoolean(false)) ((ObjectNode) h.get("hakuajat").get(0)).put("paattyy", "2028-01-18T15:00");
            targets.add(h);
        }
        reviewed.put("tila", "arkistoitu");
        target.put("tila", "arkistoitu");
        return JSON.writeValueAsString(root);
    }

    @Test void theOneVerifiedSuccessorOfANewYearIsAdoptedAndCarriesTheNewCycle() throws Exception {
        String json = nextYear(helsinki, 1, null, null);
        assertThat(CuratedSources.successor(helsinki, json)).contains(NEXT);
        var adopted = helsinki.withImplementation(NEXT);
        CuratedSources.validateStudyinfo(adopted, json);
        var parsed = StudyinfoParser.parse(json, FieldTaxonomy.of(Map.of()), NEXT, adopted.combined(), adopted.providerOid(),
                adopted.titleCodes(), adopted.reviewedField());
        assertThat(parsed.program().degreeLevel()).isEqualTo("MASTER");
        assertThat(parsed.program().deadlines()).extracting(StudyinfoParser.Deadline::date).containsExactly("2028-01-18");
        assertThat(StudyinfoParser.toFacts(parsed.program())).filteredOn(f -> f.type() == ExtractedFact.FactType.TUITION)
                .singleElement().satisfies(f -> assertThat(f.values()).containsEntry("academicYear", "2028/2029"));
        assertThat(adopted.educationOid()).as("the program identity never changes").isEqualTo(helsinki.educationOid());
    }

    @Test void aStillPublishedReviewedImplementationIsKept() throws Exception {
        assertThat(CuratedSources.successor(helsinki, CuratedSourceEvidenceTest.fixture(helsinki.key() + ".json"))).isEmpty();
    }

    @Test void twoCandidatesAnotherProviderOrAnotherLanguageStayAReviewAlert() throws Exception {
        assertThat(CuratedSources.successor(helsinki, nextYear(helsinki, 2, null, null))).as("two successors").isEmpty();
        assertThat(CuratedSources.successor(helsinki, nextYear(helsinki, 1, "1.2.246.562.10.00000000099", null))).as("other unit").isEmpty();
        assertThat(CuratedSources.successor(helsinki, nextYear(helsinki, 1, null, "oppilaitoksenopetuskieli_1#1"))).as("Finnish").isEmpty();
        // and the unchanged validation still refuses the archived reviewed implementation, naming the candidates
        assertThatThrownBy(() -> CuratedSources.validateStudyinfo(helsinki, nextYear(helsinki, 2, null, null)))
                .hasMessageContaining("NEW ADMISSION CYCLE");
    }
}
