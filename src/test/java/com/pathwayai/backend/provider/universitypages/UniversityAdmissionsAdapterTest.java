package com.pathwayai.backend.provider.universitypages;

import static org.assertj.core.api.Assertions.*;
import com.pathwayai.backend.refresh.extract.ExtractedFact;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

/** Admissions guidance is stored as short structured values plus the official link, never as copied passages. */
class UniversityAdmissionsAdapterTest {
    final CuratedSources sources=new CuratedSources(new DefaultResourceLoader(),"classpath:reference-data/curated-finland.json");
    CuratedSources.Candidate candidate(String key){return sources.candidates().stream().filter(c->c.key().equals(key)).findFirst().orElseThrow();}
    static Map<String,String> values(List<ExtractedFact> facts){
        return facts.stream().collect(Collectors.toMap(f->f.values().get("field"),f->f.values().get("value")));
    }

    @Test void languageTestsAreReadAsTestAndMinimumScoreFromTheRecordedLutPage() throws Exception {
        var spec=new UniversityAdmissionsAdapter.SourceSpec("lut-university","MASTER","LANGUAGE",
                "https://www.lut.fi/en/studies/apply-lut/applying-masters-programmes/rolling-admission-masters-studies/english-proficiency-for-masters-programmes",
                "English proficiency for master's programmes");
        String html=CuratedSourceEvidenceTest.fixture("support-lut-english-proficiency-masters.html");
        var v=values(UniversityAdmissionsAdapter.extract(html,spec.url(),spec,candidate("lut-software-engineering")));
        assertThat(v.get("language_tests")).startsWith("IELTS Academic 6.5; New TOEFL iBT 4.5; TOEFL iBT 92; PTE Academic 68")
                .doesNotContain("also accepted");
        assertThat(v).containsEntry("instructions",spec.url());
    }

    @Test void longGuidanceIsReducedToItsLinkAndShortValuesAreKept() {
        var spec=new UniversityAdmissionsAdapter.SourceSpec("lut-university","MASTER","ADMISSIONS",
                "https://www.lut.fi/en/studies/apply-lut/applying-masters-programmes","Applying to Master's programmes");
        String prose="Synthetic requirement sentence. ".repeat(40);
        String html="<html><body><h1>Applying to Master’s programmes</h1><h2>Eligibility</h2><p>"+prose+"</p>"
                +"<h2>Selection criteria</h2><p>Synthetic short criterion.</p></body></html>";
        var facts=UniversityAdmissionsAdapter.extract(html,spec.url(),spec,candidate("lut-business-analytics"));
        var v=values(facts);
        assertThat(v).doesNotContainKey("eligibility").containsEntry("official_admissions_url",spec.url())
                .containsKey("selection");
        facts.forEach(f->assertThat(f.values().get("value")).hasSizeLessThanOrEqualTo(UniversityProgramAdapter.MAX_VALUE));
    }

    @Test void guidanceNeverCrossesUniversityOrEntryLevel() {
        var bachelorOnly=new UniversityAdmissionsAdapter.SourceSpec("lut-university","BACHELOR","ADMISSIONS",
                "https://www.lut.fi/en/studies/apply-lut/applying-bachelors-programmes","Applying to bachelor's programmes");
        assertThat(UniversityAdmissionsAdapter.applies(bachelorOnly,candidate("lut-software-engineering"))).isFalse();
        assertThat(UniversityAdmissionsAdapter.applies(bachelorOnly,candidate("lut-digital-business"))).isTrue();
        assertThat(UniversityAdmissionsAdapter.applies(bachelorOnly,candidate("oulu-computer-science"))).isFalse();
    }

    @Test void everySupportingSourceIsUniversityOwnedAndDistinct() {
        var adapter=new UniversityAdmissionsAdapter(sources,null,new DefaultResourceLoader(),"classpath:reference-data/curated-supporting-sources.json");
        assertThat(adapter.sources()).extracting(UniversityAdmissionsAdapter.SourceSpec::url).doesNotHaveDuplicates();
        assertThat(adapter.sources()).allMatch(s->CuratedSources.owned(s.url(),s.university()));
        assertThat(adapter.sources()).extracting(UniversityAdmissionsAdapter.SourceSpec::university).contains(
                "aalto-university","university-of-oulu","tampere-university","lut-university","university-of-jyvaskyla","university-of-turku");
    }
}
