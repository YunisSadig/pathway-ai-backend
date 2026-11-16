package com.pathwayai.backend.provider.universitypages;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class OfficialProgramPagesTest {
    static final String URL = "https://www.oulu.fi/en/apply/masters-synthetic";
    static final OfficialProgramPages.Match MATCH = new OfficialProgramPages.Match("synthetic-oid",
            "university-of-oulu", "Synthetic Programme", "MASTER", URL, "Synthetic Programme", "Master of Science");
    static final String HTML = "<main><h1>Synthetic Programme</h1><h3>Degree title</h3><p>Master of Science</p></main>";

    @Test void acceptsAnExactProgramHeadingAndQualification() {
        assertThatCode(() -> MATCH.verify(URL, "text/html;charset=UTF-8", HTML)).doesNotThrowAnyException();
    }

    @Test void rejectsHomepagesWrongInstitutionsPortsCredentialsAndLookalikeHosts() {
        for (String url : new String[]{"https://www.oulu.fi/", "https://www.aalto.fi/en/apply/masters-synthetic",
                "https://www.oulu.fi.evil.test/en/apply/masters-synthetic", "http://www.oulu.fi/en/apply/masters-synthetic",
                "https://user@www.oulu.fi/en/apply/masters-synthetic", "https://www.oulu.fi:8443/en/apply/masters-synthetic"}) {
            assertThat(OfficialProgramPages.universityOwned("university-of-oulu", url)).isFalse();
        }
    }

    @Test void rejectsRedirectsWrongLevelsMissingHeadingsAndNonHtml() {
        assertThatThrownBy(() -> MATCH.verify("https://www.oulu.fi/", "text/html", HTML)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MATCH.verify(URL, "application/json", HTML)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MATCH.verify(URL, "text/html", HTML.replace("Master of Science", "Bachelor of Science")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MATCH.verify(URL, "text/html", HTML.replace("h1", "h2"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MATCH.verify(URL, "text/html", "<h1>Other Programme</h1>" + HTML)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void unknownLinksRemainUnknownAndStudyinfoIsOnlyASource() {
        assertThat(OfficialProgramPages.universityOwned("university-of-oulu", null)).isFalse();
        assertThat(OfficialProgramPages.studyinfo("https://opintopolku.fi/konfo/en/koulutus/123")).isTrue();
        assertThat(OfficialProgramPages.studyinfo("https://opintopolku.fi.evil.test/")).isFalse();
        assertThat(OfficialProgramPages.studyinfo(null)).isFalse();
    }

    @Test void reviewedIdentifiersAndDestinationsAreUnique() {
        assertThat(OfficialProgramPages.REVIEWED).extracting(OfficialProgramPages.Match::oid).doesNotHaveDuplicates();
        assertThat(OfficialProgramPages.REVIEWED).extracting(OfficialProgramPages.Match::url).doesNotHaveDuplicates();
    }

    @Test void listAndDetailHideLegacyStudyinfoDestinationsWithoutChangingTheEntity() {
        var university = com.pathwayai.backend.support.SyntheticFixtures.university(
                com.pathwayai.backend.support.SyntheticFixtures.countryA(), "synthetic-university", "Synthetic University", null);
        var program = com.pathwayai.backend.support.SyntheticFixtures.program(university, "synthetic", "Synthetic Programme");
        String legacy = "https://opintopolku.fi/konfo/en/koulutus/synthetic";
        com.pathwayai.backend.support.ProgramFixtures.describe(program, null, null, legacy);
        var mapper = new com.pathwayai.backend.program.mapper.ProgramMapper(
                new com.pathwayai.backend.program.calendar.AcademicCalendar(java.time.Clock.systemUTC()));
        assertThat(mapper.toListItem(program).officialProgramUrl()).isNull();
        assertThat(mapper.toDetail(program, java.util.List.of(), java.util.List.of(), java.util.List.of()).officialProgramUrl()).isNull();
        assertThat(program.getOfficialProgramUrl()).isEqualTo(legacy);
    }
}
