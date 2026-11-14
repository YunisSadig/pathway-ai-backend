package com.pathwayai.backend.program.api;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
 * A fee that states only a start date ("for study rights beginning on or after D") is the current fee only until the
 * source states a later start date that has arrived. A start date alone never proves a fee applies to a later year.
 * Synthetic data; the dates are far from today so the test does not depend on when it runs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TuitionSupersessionIT {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("insert into country (iso2_code, name) values ('ZT', 'Tuitionland')");
        long country = jdbc.queryForObject("select id from country where iso2_code = 'ZT'", Long.class);
        long uni = jdbc.queryForObject("""
                insert into university (country_id, slug, name, verification_status, verified_on)
                values (?, 'zz-sup-u', 'Synthetic Supersession University', 'VERIFIED', current_date) returning id""", Long.class, country);
        long source = jdbc.queryForObject("""
                insert into source (url, title, publisher, source_type, retrieved_on)
                values ('https://www.zz-sup.example/fees', 'Fees', 'Synthetic', 'UNIVERSITY_WEBSITE', current_date) returning id""", Long.class);
        // a: 15000 from 2000-01-01, then 17000 from 2001-01-01 (that start has long arrived): the older one is superseded
        long a = program(uni, "a");
        fee(a, source, "15000", "2000-01-01");
        fee(a, source, "17000", "2001-01-01");
        // b: 15000 from 2000-01-01, then 17000 from 2099-01-01 (not arrived): the older one is still the current fee
        long b = program(uni, "b");
        fee(b, source, "15000", "2000-01-01");
        fee(b, source, "17000", "2099-01-01");
    }

    @AfterEach
    void clean() {
        jdbc.update("delete from university where slug = 'zz-sup-u'");
        jdbc.update("delete from source where url = 'https://www.zz-sup.example/fees'");
        jdbc.update("delete from country where iso2_code = 'ZT'");
    }

    long program(long uni, String slug) {
        return jdbc.queryForObject("""
                insert into degree_program (university_id, slug, name, degree_level, verification_status, verified_on)
                values (?, ?, ?, 'MASTER', 'VERIFIED', current_date) returning id""", Long.class, uni, slug, "Synthetic " + slug);
    }

    void fee(long program, long source, String amount, String from) {
        jdbc.update("""
                insert into tuition_fee (program_id, source_id, student_category, amount, currency, billing_period, effective_from)
                values (?, ?, 'INTERNATIONAL', ?::numeric, 'EUR', 'PER_YEAR', ?::date)""", program, source, amount, from);
    }

    @Test
    void anEarlierOpenEndedPeriodBecomesHistoricalOnceALaterStartHasArrived() throws Exception {
        mockMvc.perform(get("/api/v1/programs/zz-sup-u--a")).andExpect(status().isOk())
                .andExpect(jsonPath("$.tuition[?(@.amount == 15000)].temporalStatus").value(containsInAnyOrder("HISTORICAL")))
                .andExpect(jsonPath("$.tuition[?(@.amount == 17000)].temporalStatus").value(containsInAnyOrder("CURRENT")));
    }

    @Test
    void aLaterPeriodThatHasNotStartedYetSupersedesNothing() throws Exception {
        mockMvc.perform(get("/api/v1/programs/zz-sup-u--b")).andExpect(status().isOk())
                .andExpect(jsonPath("$.tuition[?(@.amount == 15000)].temporalStatus").value(containsInAnyOrder("CURRENT")))
                .andExpect(jsonPath("$.tuition[?(@.amount == 17000)].temporalStatus").value(containsInAnyOrder("CURRENT")));
    }

    @Test
    void thePriceFilterNeverMatchesASupersededFee() throws Exception {
        // a's cheap 15000 fee is superseded by 17000: it must not make a match for "at most 16000"
        mockMvc.perform(get("/api/v1/programs").param("tuitionMax", "16000").param("tuitionCurrency", "EUR")
                        .param("tuitionCategory", "INTERNATIONAL").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.identifier =~ /zz-sup-u.*/)].identifier").value(containsInAnyOrder("zz-sup-u--b")));
        mockMvc.perform(get("/api/v1/programs").param("tuitionMax", "18000").param("tuitionCurrency", "EUR")
                        .param("tuitionCategory", "INTERNATIONAL").param("size", "100"))
                .andExpect(jsonPath("$.content[?(@.identifier =~ /zz-sup-u.*/)]", hasSize(2)));
    }
}
