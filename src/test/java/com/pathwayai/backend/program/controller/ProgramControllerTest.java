package com.pathwayai.backend.program.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pathwayai.backend.common.exception.ResourceNotFoundException;
import com.pathwayai.backend.common.response.PageResponse;
import com.pathwayai.backend.program.dto.ProgramListItemResponse;
import com.pathwayai.backend.program.entity.DegreeLevel;
import com.pathwayai.backend.program.service.ProgramSearchCriteria;
import com.pathwayai.backend.program.service.ProgramService;
import com.pathwayai.backend.tuition.entity.StudentCategory;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProgramController.class)
class ProgramControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean ProgramService service;

    @Test
    void listUsesDefaultsAndReturnsTheEnvelope() throws Exception {
        when(service.search(any(), any())).thenReturn(new PageResponse<ProgramListItemResponse>(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/api/v1/programs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(20));

        ArgumentCaptor<ProgramSearchCriteria> criteria = ArgumentCaptor.forClass(ProgramSearchCriteria.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(service).search(criteria.capture(), pageable.capture());
        assertThat(criteria.getValue()).isEqualTo(ProgramSearchCriteria.none());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
        assertThat(pageable.getValue().getSort().toString()).startsWith("name: ASC");
    }

    @Test
    void listParsesEveryFilterAndMapsTheSortKey() throws Exception {
        when(service.search(any(), any())).thenReturn(new PageResponse<ProgramListItemResponse>(List.of(), 1, 5, 0, 0));

        mockMvc.perform(get("/api/v1/programs")
                        .param("q", "data").param("field", "computer-science").param("level", "Master")
                        .param("country", "fi").param("university", "some-university").param("language", "en")
                        .param("tuitionMax", "12000.50").param("tuitionCurrency", "eur")
                        .param("tuitionCategory", "International")
                        .param("page", "1").param("size", "5").param("sort", "university")
                        .param("direction", "DESC"))
                .andExpect(status().isOk());

        ArgumentCaptor<ProgramSearchCriteria> criteria = ArgumentCaptor.forClass(ProgramSearchCriteria.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(service).search(criteria.capture(), pageable.capture());
        assertThat(criteria.getValue()).isEqualTo(new ProgramSearchCriteria("data", "computer-science",
                DegreeLevel.MASTER, "fi", "some-university", "en", new BigDecimal("12000.50"), "eur",
                StudentCategory.INTERNATIONAL));
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageable.getValue().getSort().toString()).startsWith("university.name: DESC");
    }

    @Test
    void invalidParametersNeverReachTheService() throws Exception {
        mockMvc.perform(get("/api/v1/programs").param("level", "phd")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].field").value("level"));
        mockMvc.perform(get("/api/v1/programs").param("sort", "tuition")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/programs").param("size", "500")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/programs").param("tuitionMax", "-5").param("tuitionCurrency", "EUR"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void detailPassesTheIdentifierThrough() throws Exception {
        when(service.getByIdentifier("some-uni--some-program"))
                .thenThrow(new ResourceNotFoundException("Program", "some-uni--some-program"));
        mockMvc.perform(get("/api/v1/programs/some-uni--some-program"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Program not found: some-uni--some-program"));
    }

    @Test
    void malformedIdentifiersAreRejectedBeforeTheService() throws Exception {
        mockMvc.perform(get("/api/v1/programs/nodelimiter")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/programs/UPPER--case")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/programs/" + "a".repeat(250) + "--b")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
