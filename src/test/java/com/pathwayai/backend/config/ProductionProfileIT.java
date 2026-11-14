package com.pathwayai.backend.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pathwayai.backend.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** The production profile boots, keeps the API behind the staging key, leaves the platform health probe open and exposes nothing else. */
@SpringBootTest(properties = {"app.access.staging-key=it-staging-key", "spring.flyway.enabled=true"})
@ActiveProfiles("prod")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProductionProfileIT {

    @Autowired MockMvc mockMvc;

    @Test
    void apiNeedsTheStagingKeyButTheHealthProbeDoesNot() throws Exception {
        mockMvc.perform(get("/api/v1/programs")).andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control", "no-store"));
        mockMvc.perform(get("/api/v1/programs").header("X-Staging-Key", "wrong")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/programs").header("X-Staging-Key", "it-staging-key")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void noOtherActuatorEndpointIsExposedAndHealthShowsNoDetails() throws Exception {
        mockMvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/beans")).andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.components").doesNotExist());
    }
}
