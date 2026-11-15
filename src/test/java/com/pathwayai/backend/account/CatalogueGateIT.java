package com.pathwayai.backend.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pathwayai.backend.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
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
 * While the Studyinfo reuse conditions are unresolved the academic catalogue must stay closed to everybody except accounts an
 * administrator opened it for: signing up is not authorization. Synthetic data only.
 */
@SpringBootTest(properties = {"app.access.catalogue-public=false", "app.discovery.restrict-to-verified-fresh=false"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CatalogueGateIT {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("insert into country (iso2_code, name) values ('ZG', 'Gateland')");
        long country = jdbc.queryForObject("select id from country where iso2_code = 'ZG'", Long.class);
        long uni = jdbc.queryForObject("""
                insert into university (country_id, slug, name, verification_status, verified_on)
                values (?, 'zz-gate-u', 'Synthetic Gate University', 'VERIFIED', current_date) returning id""", Long.class, country);
        jdbc.update("""
                insert into degree_program (university_id, slug, name, degree_level, verification_status, verified_on)
                values (?, 'p', 'Synthetic Gate Program', 'MASTER', 'VERIFIED', current_date)""", uni);
    }

    @AfterEach
    void clean() {
        jdbc.update("delete from user_account where email_normalized like '%@it.example'");
        jdbc.update("delete from auth_throttle");
        jdbc.update("delete from spring_session");
        jdbc.update("delete from university where slug = 'zz-gate-u'");
        jdbc.update("delete from country where iso2_code = 'ZG'");
    }

    Cookie register(String email) throws Exception {
        return mvc.perform(post("/api/v1/account/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + AccountApiIT.PASSWORD + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getCookie("PATHWAY_SESSION");
    }

    void grant(String email, boolean on) {
        jdbc.update("update user_account set catalogue_access = ? where email_normalized = ?", on, email);
    }

    @Test
    void anonymousVisitorsGetNothingFromTheCatalogue() throws Exception {
        for (String path : new String[] {"/api/v1/programs", "/api/v1/programs/zz-gate-u--p", "/api/v1/universities",
                "/api/v1/universities/zz-gate-u", "/api/v1/discovery/status"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control", "no-store"));
        }
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk()); // the platform probe stays open
    }

    @Test
    void aSignedUpAccountWithoutCatalogueAccessIsRefusedAndAGrantedOneIsServedPrivately() throws Exception {
        String email = AccountApiIT.email();
        Cookie session = register(email);
        mvc.perform(get("/api/v1/programs").cookie(session)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CATALOGUE_PROTECTED"));
        mvc.perform(get("/api/v1/universities/zz-gate-u").cookie(session)).andExpect(status().isForbidden());
        grant(email, true);
        mvc.perform(get("/api/v1/programs/zz-gate-u--p").cookie(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Synthetic Gate Program"))
                .andExpect(header().string("Cache-Control", startsWith("private"))); // a shared cache must never hold gated data
        grant(email, false);
        mvc.perform(get("/api/v1/programs/zz-gate-u--p").cookie(session)).andExpect(status().isForbidden()); // revocation is immediate
    }

    @Test
    void savedItemsWorkWithAccessAndResolveThroughTheCatalogueRules() throws Exception {
        String email = AccountApiIT.email();
        Cookie session = register(email);
        grant(email, true);
        mvc.perform(put("/api/v1/account/saved/programs/zz-gate-u--p").cookie(session)).andExpect(status().isNoContent());
        mvc.perform(put("/api/v1/account/saved/programs/zz-gate-u--p").cookie(session)).andExpect(status().isNoContent()); // idempotent
        mvc.perform(put("/api/v1/account/saved/universities/zz-gate-u").cookie(session)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/account/saved").cookie(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.programs.length()").value(1))
                .andExpect(jsonPath("$.programs[0].available").value(true))
                .andExpect(jsonPath("$.programs[0].name").value("Synthetic Gate Program"))
                .andExpect(jsonPath("$.programs[0].universityName").value("Synthetic Gate University"))
                .andExpect(jsonPath("$.universities[0].name").value("Synthetic Gate University"))
                .andExpect(header().string("Cache-Control", "no-store"));
        // revoked access: saved items still resolve through the public tier (name, level, university), never the protected catalogue
        grant(email, false);
        mvc.perform(get("/api/v1/account/saved").cookie(session)).andExpect(jsonPath("$.catalogueVisible").value(false))
                .andExpect(jsonPath("$.programs[0].available").value(true)).andExpect(jsonPath("$.programs[0].name").value("Synthetic Gate Program"));
        grant(email, true);
        mvc.perform(delete("/api/v1/account/saved/programs/zz-gate-u--p").cookie(session)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/account/saved").cookie(session)).andExpect(jsonPath("$.programs.length()").value(0));
    }

    @Test
    void unknownOrInvalidItemsAreRefusedAndAProgramThatLeavesTheCatalogueShowsAsUnavailable() throws Exception {
        String email = AccountApiIT.email();
        Cookie session = register(email);
        grant(email, true);
        mvc.perform(put("/api/v1/account/saved/programs/zz-gate-u--missing").cookie(session)).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/account/saved/programs/Not_Valid!").cookie(session)).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/account/saved/universities/zz-unknown").cookie(session)).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/account/saved/programs/zz-gate-u--p").cookie(session)).andExpect(status().isNoContent());
        jdbc.update("delete from degree_program where slug = 'p' and university_id = (select id from university where slug = 'zz-gate-u')");
        mvc.perform(get("/api/v1/account/saved").cookie(session)).andExpect(jsonPath("$.programs[0].identifier").value("zz-gate-u--p"))
                .andExpect(jsonPath("$.programs[0].available").value(false));
    }

    @Test
    void savedItemsBelongToOneAccountOnly() throws Exception {
        String ada = AccountApiIT.email();
        String bob = AccountApiIT.email();
        Cookie adaSession = register(ada);
        Cookie bobSession = register(bob);
        grant(ada, true);
        grant(bob, true);
        mvc.perform(put("/api/v1/account/saved/programs/zz-gate-u--p").cookie(adaSession)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/account/saved").cookie(bobSession)).andExpect(jsonPath("$.programs.length()").value(0));
        mvc.perform(delete("/api/v1/account/saved/programs/zz-gate-u--p").cookie(bobSession)).andExpect(status().isNoContent()); // nothing to remove
        mvc.perform(get("/api/v1/account/saved").cookie(adaSession)).andExpect(jsonPath("$.programs.length()").value(1));
        assertThat(jdbc.queryForObject("select count(*) from saved_program", Integer.class)).isEqualTo(1);
    }
}
