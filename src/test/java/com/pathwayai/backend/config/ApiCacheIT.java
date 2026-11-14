package com.pathwayai.backend.config;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pathwayai.backend.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The API tells every cache how long an answer may be reused, and a synchronized change reaches a client at the latest when the
 * client revalidates: a changed answer has a different ETag, an unchanged one is a cheap 304. Synthetic data only.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ApiCacheIT {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("insert into country (iso2_code, name) values ('ZC', 'Cacheland')");
        long country = jdbc.queryForObject("select id from country where iso2_code = 'ZC'", Long.class);
        long uni = jdbc.queryForObject("""
                insert into university (country_id, slug, name, verification_status, verified_on)
                values (?, 'zz-cache-u', 'Synthetic Cache University', 'VERIFIED', current_date) returning id""", Long.class, country);
        jdbc.update("""
                insert into degree_program (university_id, slug, name, degree_level, verification_status, verified_on)
                values (?, 'p', 'Synthetic Program', 'MASTER', 'VERIFIED', current_date)""", uni);
    }

    @AfterEach
    void clean() {
        jdbc.update("delete from university where slug = 'zz-cache-u'");
        jdbc.update("delete from country where iso2_code = 'ZC'");
    }

    @Test
    void listsAndDetailsMayBeReusedForFiveMinutesThenMustBeRevalidated() throws Exception {
        for (String path : new String[] {"/api/v1/programs", "/api/v1/universities", "/api/v1/programs/zz-cache-u--p"}) {
            mockMvc.perform(get(path)).andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", equalTo("public, max-age=300, must-revalidate")))
                    .andExpect(header().string("ETag", notNullValue()));
        }
    }

    @Test
    void anUnchangedAnswerRevalidatesAs304() throws Exception {
        String etag = mockMvc.perform(get("/api/v1/programs/zz-cache-u--p")).andReturn().getResponse().getHeader("ETag");
        mockMvc.perform(get("/api/v1/programs/zz-cache-u--p").header("If-None-Match", etag)).andExpect(status().isNotModified());
    }

    @Test
    void aSynchronizedChangeIsVisibleAsSoonAsTheClientRevalidates() throws Exception {
        String before = mockMvc.perform(get("/api/v1/programs/zz-cache-u--p")).andReturn().getResponse().getHeader("ETag");
        jdbc.update("update degree_program set name = 'Synthetic Program (renamed by a sync)' where slug = 'p' and university_id = "
                + "(select id from university where slug = 'zz-cache-u')");
        var changed = mockMvc.perform(get("/api/v1/programs/zz-cache-u--p").header("If-None-Match", before))
                .andExpect(status().isOk()).andReturn().getResponse();
        org.assertj.core.api.Assertions.assertThat(changed.getContentAsString()).contains("renamed by a sync");
        org.assertj.core.api.Assertions.assertThat(changed.getHeader("ETag")).isNotEqualTo(before);
    }

    @Test
    void theDiscoveryStatusIsAlwaysRevalidatedBecauseItReportsWhetherTheDataIsCurrent() throws Exception {
        mockMvc.perform(get("/api/v1/discovery/status")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", equalTo("no-cache")));
    }

    @Test
    void errorsAreNeverCached() throws Exception {
        mockMvc.perform(get("/api/v1/programs/zz-cache-u--missing")).andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", equalTo("no-store")));
        mockMvc.perform(get("/api/v1/universities").param("size", "0")).andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", equalTo("no-store")));
        mockMvc.perform(get("/api/v1/programs/zz-cache-u--p")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", not(containsString("no-store"))));
    }
}
