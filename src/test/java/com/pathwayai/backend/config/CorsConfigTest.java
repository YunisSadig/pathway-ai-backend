package com.pathwayai.backend.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pathwayai.backend.common.response.PageResponse;
import com.pathwayai.backend.university.controller.UniversityController;
import com.pathwayai.backend.university.service.UniversityService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(UniversityController.class)
@Import(CorsConfig.class)
@TestPropertySource(properties = "app.cors.allowed-origins=http://localhost:3000")
class CorsConfigTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean UniversityService service;

    @Test
    void preflightFromAllowedOriginIsAccepted() throws Exception {
        mockMvc.perform(options("/api/v1/universities")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().string("Access-Control-Allow-Methods", "GET,HEAD,OPTIONS"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }

    @Test
    void actualGetFromAllowedOriginCarriesAllowOrigin() throws Exception {
        when(service.search(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));
        mockMvc.perform(get("/api/v1/universities").header("Origin", "http://localhost:3000"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
    }

    @Test
    void otherOriginIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/universities").header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void writeMethodPreflightIsRejected() throws Exception {
        mockMvc.perform(options("/api/v1/universities")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "DELETE"))
                .andExpect(status().isForbidden());
    }

    @Test
    void wildcardOriginIsRefusedAtStartup() {
        assertThatThrownBy(() -> new CorsConfig(List.of("*")))
                .isInstanceOf(IllegalStateException.class);
    }
}
