package com.pathwayai.backend.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pathwayai.backend.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Public university discovery: a visitor with NO session sees an allow-listed set of identity fields and nothing else; everything
 * academic stays behind the catalogue gate. Synthetic data only.
 */
@SpringBootTest(properties = {"app.access.catalogue-public=false", "app.discovery.restrict-to-verified-fresh=false"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PublicCatalogueIT {

    static final Set<String> APPROVED_FIELDS = Set.of("slug", "name", "city", "countryCode", "countryName", "websiteUrl", "trackedPrograms", "verifiedOn");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("insert into country (iso2_code, name) values ('ZP', 'Publicland')");
        long country = jdbc.queryForObject("select id from country where iso2_code = 'ZP'", Long.class);
        long uni = jdbc.queryForObject("""
                insert into university (country_id, slug, name, city, website_url, description, verification_status, verified_on)
                values (?, 'zz-public-u', 'Synthetic Public University', 'Testville', 'https://example.test/zz', 'SECRET DESCRIPTION TEXT', 'VERIFIED', date '2026-11-01')
                returning id""", Long.class, country);
        jdbc.update("""
                insert into degree_program (university_id, slug, name, degree_level, verification_status, verified_on)
                values (?, 'p', 'Synthetic Secret Program Name', 'MASTER', 'VERIFIED', current_date)""", uni);
    }

    @AfterEach
    void clean() {
        jdbc.update("delete from user_account where email_normalized like '%@it.example'");
        jdbc.update("delete from auth_throttle");
        jdbc.update("delete from spring_session");
        jdbc.update("delete from university where slug = 'zz-public-u'");
        jdbc.update("delete from country where iso2_code = 'ZP'");
    }

    Cookie register(String email) throws Exception {
        return mvc.perform(post("/api/v1/account/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + AccountApiIT.PASSWORD + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getCookie("PATHWAY_SESSION");
    }

    @Test
    void aVisitorWithoutASessionSeesOnlyTheApprovedIdentityFields() throws Exception {
        String list = mvc.perform(get("/api/v1/public/universities?q=Synthetic")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1))).andExpect(jsonPath("$.content[0].slug").value("zz-public-u"))
                .andExpect(jsonPath("$.content[0].name").value("Synthetic Public University"))
                .andExpect(jsonPath("$.content[0].city").value("Testville"))
                .andExpect(jsonPath("$.content[0].countryName").value("Publicland"))
                .andExpect(jsonPath("$.content[0].websiteUrl").value("https://example.test/zz"))
                .andExpect(jsonPath("$.content[0].trackedPrograms").value(1))
                .andExpect(jsonPath("$.content[0].verifiedOn").value("2026-11-01"))
                .andReturn().getResponse().getContentAsString();
        String detail = mvc.perform(get("/api/v1/public/universities/zz-public-u")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for (String body : new String[] {list, detail}) {
            // nothing academic and no free text leaves through the public door
            assertThat(body).doesNotContain("SECRET DESCRIPTION").doesNotContain("Secret Program").doesNotContain("MASTER")
                    .doesNotContain("degreeLevel").doesNotContain("tuition").doesNotContain("deadline").doesNotContain("description")
                    .doesNotContain("programs\"");
        }
        // the exact field set is pinned: adding a field must be a deliberate change to this test
        assertThat(detail.replaceAll("[{}\"]", "").split(",")).extracting(kv -> kv.split(":", 2)[0]).containsExactlyInAnyOrderElementsOf(APPROVED_FIELDS);
    }

    @Test
    void searchFindsByNameAndSaysNothingForAnUnknownOne() throws Exception {
        mvc.perform(get("/api/v1/public/universities?q=nothing-like-this")).andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(0)));
        mvc.perform(get("/api/v1/public/universities/zz-no-such-university")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/public/universities/Bad_Slug")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/public/universities?size=500")).andExpect(status().isBadRequest());
    }

    @Test
    void theAcademicCatalogueStaysClosedToVisitors() throws Exception {
        for (String path : new String[] {"/api/v1/programs", "/api/v1/programs/zz-public-u--p", "/api/v1/universities", "/api/v1/universities/zz-public-u",
                "/api/v1/discovery/status"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void publicDataIsNotWritableAndStudentProfilesStayPrivate() throws Exception {
        mvc.perform(put("/api/v1/public/universities/zz-public-u")).andExpect(status().is4xxClientError());
        mvc.perform(get("/api/v1/account/student-profile")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/account/saved")).andExpect(status().isUnauthorized());
    }

    @Test
    void anyAccountCanSaveUniversitiesAndPrograms() throws Exception {
        Cookie session = register(AccountApiIT.email());
        mvc.perform(put("/api/v1/account/saved/universities/zz-public-u").cookie(session)).andExpect(status().isNoContent());
        mvc.perform(put("/api/v1/account/saved/universities/zz-no-such-university").cookie(session)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/account/saved").cookie(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.universities", hasSize(1))).andExpect(jsonPath("$.universities[0].available").value(true))
                .andExpect(jsonPath("$.universities[0].name").value("Synthetic Public University"))
                .andExpect(jsonPath("$.catalogueVisible").value(false));
        mvc.perform(put("/api/v1/account/saved/programs/zz-public-u--p").cookie(session)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/account/saved").cookie(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.programs", hasSize(1))).andExpect(jsonPath("$.programs[0].available").value(true));
    }

    static final Set<String> PROGRAM_FIELDS = Set.of("identifier", "slug", "name", "degreeLevel", "field", "studyMode", "durationMonths",
            "teachingLanguages", "campusCity", "university", "officialProgramUrl", "officialAdmissionsUrl", "officialApplicationUrl", "verification");
    static final Set<String> PROGRAM_DETAIL_FIELDS = new java.util.HashSet<>(PROGRAM_FIELDS) {{ add("lastCheckedOn"); add("catalogMembership"); }};

    @Test
    void visitorsBrowseProgramsWithAnAllowListAndNothingRestricted() throws Exception {
        String list = mvc.perform(get("/api/v1/public/programs?q=Synthetic&level=master&country=ZP")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1))).andExpect(jsonPath("$.content[0].identifier").value("zz-public-u--p"))
                .andExpect(jsonPath("$.content[0].university.name").value("Synthetic Public University"))
                .andExpect(jsonPath("$.content[0].officialApplicationUrl").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String one = mvc.perform(get("/api/v1/public/programs/zz-public-u--p")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for (String body : new String[] {list, one}) {
            assertThat(body).doesNotContain("tuition").doesNotContain("requirements").doesNotContain("intakes").doesNotContain("sources")
                    .doesNotContain("synchronization").doesNotContain("applicationDeadline");
        }
        assertThat(tools.jackson.databind.json.JsonMapper.builder().build().readTree(one).propertyNames())
                .containsExactlyInAnyOrderElementsOf(PROGRAM_DETAIL_FIELDS);
        mvc.perform(get("/api/v1/public/programs?level=doctorate")).andExpect(jsonPath("$.content", hasSize(0)));
        mvc.perform(get("/api/v1/public/programs/zz-public-u--nope")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/public/programs?size=500")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/public/universities?country=ZP")).andExpect(jsonPath("$.content", hasSize(1)));
        mvc.perform(get("/api/v1/public/universities?country=FI")).andExpect(jsonPath("$.content", hasSize(0)));
        // the detailed protected record still needs catalogue access
        mvc.perform(get("/api/v1/programs/zz-public-u--p")).andExpect(status().isUnauthorized());
    }
}
