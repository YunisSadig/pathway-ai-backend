package com.pathwayai.backend.provider.universitypages;

import static org.assertj.core.api.Assertions.*;
import com.pathwayai.backend.provider.studyinfo.FieldTaxonomy;
import com.pathwayai.backend.provider.studyinfo.StudyinfoParser;
import com.pathwayai.backend.refresh.extract.ExtractedFact;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * The 20 reviewed offerings against the official responses recorded on 2026-11-17 (Studyinfo external API records and
 * the universities' own program pages, in src/test/resources/curated20). No live site is contacted.
 */
class CuratedSourceEvidenceTest {
    static final LocalDate RECORDED=LocalDate.of(2026,11,17);
    final CuratedSources sources=new CuratedSources(new DefaultResourceLoader(),"classpath:reference-data/curated-finland.json");

    static String fixture(String path) throws Exception {
        try(var in=CuratedSourceEvidenceTest.class.getResourceAsStream("/curated20/"+path)) {
            if(in==null)throw new IllegalArgumentException(path);
            return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    CuratedSources.Candidate candidate(String key){return sources.candidates().stream().filter(c->c.key().equals(key)).findFirst().orElseThrow();}
    static StudyinfoParser.Parsed parse(CuratedSources.Candidate c,String json){
        return StudyinfoParser.parse(json,FieldTaxonomy.of(Map.of()),c.implementationOid(),c.combined(),c.providerOid(),c.titleCodes(),c.reviewedField());
    }
    static List<ExtractedFact> page(CuratedSources.Candidate c) throws Exception {
        try { return UniversityProgramAdapter.extract(fixture(c.key()+".html"),c.programUrl(),c); }
        catch(IllegalArgumentException e) { throw new AssertionError(c.key()+": "+e.getMessage(),e); }
    }
    static Map<String,ExtractedFact> byField(List<ExtractedFact> facts){
        var m=new java.util.LinkedHashMap<String,ExtractedFact>();
        facts.forEach(f->m.put(f.values().get("field"),f));
        return m;
    }

    @Test void allTwentyAreEnglishPublishedOfferingsWithACurrentRouteAndAMatchingUniversityPage() throws Exception {
        int bachelors=0,masters=0;
        for(var c:sources.candidates()) {
            String json=fixture(c.key()+".json");
            CuratedSources.validateStudyinfo(c,json);
            var parsed=parse(c,json);
            assertThat(parsed.program()).as(c.key()+" "+parsed.issues()).isNotNull();
            assertThat(parsed.program().degreeLevel()).as(c.key()+" "+parsed.issues()).isEqualTo(c.entryLevel());
            assertThat(parsed.program().toteutusOid()).isEqualTo(c.implementationOid());
            assertThat(parsed.program().languages()).as(c.key()).containsExactly("en");
            assertThat(parsed.program().deadlines()).as(c.key()+" needs a current/upcoming route")
                    .anyMatch(d->!LocalDate.parse(d.date()).isBefore(RECORDED));
            var facts=byField(page(c));
            assertThat(facts).as(c.key()).containsKeys("official_program_url","tuition_scope");
            assertThat(facts.get("official_program_url").values().get("value")).isEqualTo(c.programUrl());
            facts.values().forEach(f->assertThat(f.values().get("value")).as(c.key()+" keeps short values only").hasSizeLessThanOrEqualTo(400));
            if(c.entryLevel().equals("BACHELOR")) {
                bachelors++;
                assertThat(parsed.program().durationMonths()).as(c.key()).isEqualTo(c.combined()?60:36);
            } else {
                masters++;
                assertThat(c.combined()).isFalse();
                assertThat(parsed.program().durationMonths()).as(c.key()).isEqualTo(24);
            }
        }
        assertThat(bachelors).isEqualTo(10);
        assertThat(masters).isEqualTo(10);
    }

    @Test void sevenUniversitiesAndNoProgramIsSelectedTwice() {
        assertThat(sources.candidates()).extracting(CuratedSources.Candidate::university).containsOnly(
                "aalto-university","university-of-helsinki","tampere-university","lut-university","university-of-oulu",
                "university-of-jyvaskyla","university-of-turku");
        assertThat(sources.candidates()).extracting(CuratedSources.Candidate::educationOid).doesNotHaveDuplicates();
        assertThat(sources.candidates()).extracting(CuratedSources.Candidate::implementationOid).doesNotHaveDuplicates();
    }

    @Test void turkuIsABachelorEntryOnlyWithTheReviewedTitlesAndBachelorEligibilityNeverByName() throws Exception {
        var turku=candidate("turku-ict");
        String json=fixture("turku-ict.json");
        // its level codes alone say master: without the reviewed evidence it is not a bachelor's entry
        assertThat(StudyinfoParser.parse(json,FieldTaxonomy.of(Map.of())).program().degreeLevel()).isEqualTo("MASTER");
        assertThat(parse(turku,json).program().degreeLevel()).isEqualTo("BACHELOR");
        // a target that starts asking for a completed degree -> unknown, review
        assertThat(parse(turku,json.replace("pohjakoulutusvaatimuskouta_123","pohjakoulutusvaatimuskouta_102")).program().degreeLevel()).isNull();
        // the degree titles change -> unknown, review
        assertThat(parse(turku,json.replace("tutkintonimikekk_150","tutkintonimikekk_999")).program().degreeLevel()).isNull();
        // the name says "Bachelor and Master" in every variant above: the name never decided anything
        assertThat(json).contains("Bachelor and Master");
        // the rule cannot be attached to a master's or a standalone record
        assertThat(sources.candidates()).filteredOn(c->!c.titleCodes().isEmpty()).extracting(CuratedSources.Candidate::key).containsExactly("turku-ict");
    }

    @Test void helsinkiScienceIsOneProgramWithTwoAdmissionGroupRoutesAndItsSourcedStudyOption() throws Exception {
        var science=candidate("helsinki-science");
        String json=fixture("helsinki-science.json");
        var deadlines=parse(science,json).program().deadlines();
        assertThat(deadlines).extracting(StudyinfoParser.Deadline::date).containsExactly("2027-01-19","2027-03-23");
        assertThat(deadlines).extracting(StudyinfoParser.Deadline::label).allMatch(l->l.length()<=100)
                .anyMatch(l->l.startsWith("Admission Group 1 · ")).anyMatch(l->l.startsWith("Admission Group 2 · "));
        assertThat(CuratedSources.validateStudyOption(science,json)).isEqualTo("computer/data science");
        assertThatThrownBy(()->CuratedSources.validateStudyOption(science,json.replace("computer/data science","informatics")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(sources.candidates()).filteredOn(c->c.educationOid().equals(science.educationOid())).hasSize(1);
    }

    @Test void aaltoRoundsStaySeparateRoutesOfOneProgram() throws Exception {
        var economics=candidate("aalto-economics-bachelor");
        var deadlines=parse(economics,fixture("aalto-economics-bachelor.json")).program().deadlines();
        assertThat(deadlines).hasSize(3).extracting(StudyinfoParser.Deadline::label).doesNotHaveDuplicates();
        assertThat(deadlines).extracting(StudyinfoParser.Deadline::date).containsExactly("2027-01-22","2027-01-22","2027-03-23");
        // the Computer Science double-degree implementation is a different implementation and never selected
        var cs=candidate("aalto-computer-science");
        assertThat(parse(cs,fixture("aalto-computer-science.json")).program().name()).doesNotContain("Double degree");
    }

    @Test void feesKeepTheirStatedAudienceAndNeverGainAnAcademicYear() throws Exception {
        for(var c:sources.candidates()) {
            var fee=byField(page(c)).get("tuition_scope");
            assertThat(fee.values()).as(c.key()).containsEntry("applicantCategory","NON_EU_EEA").doesNotContainKey("academicYear");
        }
        var oulu=candidate("oulu-business-analytics");
        String value=byField(page(oulu)).get("tuition_scope").values().get("value");
        assertThat(value).startsWith("Business Analytics: 12 000 €").doesNotContain("10 000");
    }

    @Test void homepageRedirectWrongHostAndChangedHeadingAreRejected() throws Exception {
        for(var c:sources.candidates()) {
            String html=fixture(c.key()+".html");
            String home="https://"+java.net.URI.create(c.programUrl()).getHost()+"/en/";
            assertThatThrownBy(()->UniversityProgramAdapter.extract(html,home,c)).as(c.key()).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->UniversityProgramAdapter.extract(html,"https://opintopolku.fi/konfo/en/koulutus/"+c.educationOid(),c))
                    .as(c.key()).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->UniversityProgramAdapter.extract(html.replace("<h1","<h1 data-x").replaceFirst("(?s)<h1([^>]*)>.*?</h1>","<h1$1>Synthetic unrelated course</h1>"),c.programUrl(),c))
                    .as(c.key()).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void aNewAnnualImplementationIsReportedForReviewAndNeverAdoptedOrDuplicated() throws Exception {
        var c=candidate("tampere-ai-native-software");
        String next="1.2.246.562.17.00000000000099999999";
        String json=fixture(c.key()+".json").replace(c.implementationOid(),next);
        assertThatThrownBy(()->CuratedSources.validateStudyinfo(c,json)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NEW ADMISSION CYCLE").hasMessageContaining(next);
        // the reviewed parse refuses the unknown implementation instead of attaching it to the program
        assertThat(parse(c,json).program()).isNull();
    }

    static List<ExtractedFact> studyinfoFacts(CuratedSources.Candidate c) throws Exception {
        return StudyinfoParser.toFacts(parse(c,fixture(c.key()+".json")).program());
    }

    @Test void studyinfoFeesNeedAStatedAudienceAndAStatedIntakeAndConflictingStartsPublishNoFee() throws Exception {
        int fees=0;
        for(var c:sources.candidates()) {
            var tuition=studyinfoFacts(c).stream().filter(f->f.type()==ExtractedFact.FactType.TUITION).toList();
            fees+=tuition.size();
            for(var f:tuition) assertThat(f.values()).as(c.key()).containsEntry("studentCategory","INTERNATIONAL")
                    .containsEntry("currency","EUR").containsEntry("billingPeriod","PER_YEAR").containsEntry("academicYear","2027/2028");
        }
        // no audience stated: LUT x3, Turku GIM. No single stated start: Tampere CSEE (implementation 2026, rounds 2027),
        // Oulu CSE (transfer routes start in spring), Aalto CS and Economics master's (no start term stated at all)
        assertThat(fees).isEqualTo(12);
        for(String key:List.of("lut-digital-business","lut-software-engineering","lut-business-analytics","turku-global-innovation",
                "tampere-computing-sciences","oulu-computer-science","aalto-computer-science","aalto-economics-master"))
            assertThat(studyinfoFacts(candidate(key))).as(key).noneMatch(f->f.type()==ExtractedFact.FactType.TUITION);
        var aalto=studyinfoFacts(candidate("aalto-data-science")).stream().filter(f->f.type()==ExtractedFact.FactType.TUITION).findFirst().orElseThrow();
        assertThat(aalto.values()).containsEntry("amount","12000");
        assertThat(aalto.values().get("scopeNote")).contains("autumn 2027 intake");
        var tampere=candidate("tampere-computing-sciences");
        assertThat(studyinfoFacts(tampere)).noneMatch(f->f.type()==ExtractedFact.FactType.TUITION);
        assertThat(parse(tampere,fixture(tampere.key()+".json")).issues()).anyMatch(i->i.contains("different start terms"));
        assertThat(studyinfoFacts(candidate("lut-digital-business"))).noneMatch(f->f.type()==ExtractedFact.FactType.TUITION);
    }

    @Test void studyinfoStructuredFactsComeFromCodedFieldsWithTheirOwnScope() throws Exception {
        Map<String,String> isea=new java.util.HashMap<>(), aalto=new java.util.HashMap<>();
        studyinfoFacts(candidate("jyu-immersive-software-ai")).stream().filter(f->f.type()==ExtractedFact.FactType.UNIVERSITY_FACT)
                .forEach(f->isea.put(f.values().get("field"),f.values().get("value")));
        studyinfoFacts(candidate("aalto-data-science")).stream().filter(f->f.type()==ExtractedFact.FactType.UNIVERSITY_FACT)
                .forEach(f->aalto.put(f.values().get("field"),f.values().get("value")));
        assertThat(isea).containsEntry("credits","180 ECTS").containsEntry("qualification","Bachelor of Science (Technology)")
                .containsEntry("scholarship","Scholarship of €0–6,000 available").containsKey("study_mode");
        assertThat(aalto).containsEntry("credits","300 ECTS").containsEntry("scholarship","Tuition-fee scholarship of 0–100% available");
        assertThat(studyinfoFacts(candidate("aalto-data-science"))).filteredOn(f->f.type()==ExtractedFact.FactType.UNIVERSITY_FACT)
                .allSatisfy(f->assertThat(f.values()).containsEntry("scope",StudyinfoParser.STUDYINFO_SCOPE));
        // an unreviewed parse (general discovery) never carries these details
        assertThat(StudyinfoParser.parse(fixture("aalto-data-science.json"),FieldTaxonomy.of(Map.of())).program()).satisfiesAnyOf(
                p->assertThat(p).isNull(), p->assertThat(p.details()).isNull());
    }

    @Test void fieldsComeFromOfficialCodesAndAReviewedChoiceOnlyAmongTheStatedFields() throws Exception {
        var taxonomy=new com.pathwayai.backend.provider.studyinfo.FieldTaxonomy(); // the reviewed map shipped with the application
        var science=candidate("helsinki-science");
        assertThat(StudyinfoParser.parse(fixture(science.key()+".json"),taxonomy,science.implementationOid(),science.combined(),
                science.providerOid(),science.titleCodes(),science.reviewedField()).program().fieldSlug()).isEqualTo("natural-sciences");
        var tampere=candidate("tampere-computing-sciences");
        String json=fixture(tampere.key()+".json");
        assertThat(StudyinfoParser.parse(json,taxonomy,tampere.implementationOid(),tampere.combined(),tampere.providerOid(),
                tampere.titleCodes(),null).program().fieldSlug()).as("two stated fields and no reviewed choice").isNull();
        assertThat(StudyinfoParser.parse(json,taxonomy,tampere.implementationOid(),tampere.combined(),tampere.providerOid(),
                tampere.titleCodes(),tampere.reviewedField()).program().fieldSlug()).isEqualTo("computer-and-data-science");
        // the reviewed choice cannot introduce a field the record does not state
        assertThat(StudyinfoParser.parse(json,taxonomy,tampere.implementationOid(),tampere.combined(),tampere.providerOid(),
                tampere.titleCodes(),"law").program().fieldSlug()).isNull();
    }

    @Test void datesAreReadFromExplicitSourceDatesAndNeverRelabelAnExpiredPeriod() {
        assertThat(UniversityProgramAdapter.periodEnd("7 Dec 2026 – 5 Jan 2027")).isEqualTo(LocalDate.of(2027,1,5));
        assertThat(UniversityProgramAdapter.periodEnd("07.01.2027 - 21.01.2027")).isEqualTo(LocalDate.of(2027,1,21));
        assertThat(UniversityProgramAdapter.periodEnd("Ends: 30 April 2027 at 23:59")).isEqualTo(LocalDate.of(2027,4,30));
        assertThat(UniversityProgramAdapter.periodEnd("Applications open every spring")).isNull();
    }
}
