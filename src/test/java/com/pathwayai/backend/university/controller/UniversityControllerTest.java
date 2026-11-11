package com.pathwayai.backend.university.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pathwayai.backend.common.exception.ResourceNotFoundException;
import com.pathwayai.backend.common.response.PageResponse;
import com.pathwayai.backend.university.dto.UniversityDetailResponse;
import com.pathwayai.backend.university.dto.UniversitySummaryResponse;
import com.pathwayai.backend.university.service.UniversityService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(UniversityController.class)
class UniversityControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean UniversityService service;

    @Test
    void listReturnsPageEnvelopeWithDefaults() throws Exception {
        var item = new UniversitySummaryResponse("synthetic-uni", "Synthetic University", "Testville",
                "XA", "Synthetic Land A", "https://example.invalid/x");
        when(service.search(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageResponse<>(List.of(item), 0, 20, 1, 1));

        mockMvc.perform(get("/api/v1/universities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].slug").value("synthetic-uni"))
                .andExpect(jsonPath("$.content[0].countryCode").value("XA"))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(service).search(isNull(), isNull(), pageable.capture());
        org.assertj.core.api.Assertions.assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
        org.assertj.core.api.Assertions.assertThat(pageable.getValue().getSort().toString())
                .startsWith("name: ASC");
    }

    @Test
    void listPassesFiltersAndSorting() throws Exception {
        when(service.search(eq("tech"), eq("xa"), any(Pageable.class)))
                .thenReturn(new PageResponse<>(List.of(), 2, 5, 0, 0));

        mockMvc.perform(get("/api/v1/universities")
                        .param("q", "tech").param("country", "xa").param("page", "2")
                        .param("size", "5").param("sort", "city").param("direction", "DESC"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(service).search(eq("tech"), eq("xa"), pageable.capture());
        org.assertj.core.api.Assertions.assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(pageable.getValue().getSort().toString())
                .startsWith("city: DESC");
    }

    @Test
    void invalidListParametersReturn400WithViolations() throws Exception {
        mockMvc.perform(get("/api/v1/universities")
                        .param("size", "101").param("page", "-1").param("sort", "password")
                        .param("direction", "sideways").param("country", "USA"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.path").value("/api/v1/universities"))
                .andExpect(jsonPath("$.violations.length()").value(5));
        verifyNoInteractions(service);
    }

    @Test
    void nonNumericPageReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/universities").param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].field").value("page"));
        verify(service, never()).search(any(), any(), any());
    }

    @Test
    void detailReturnsUniversity() throws Exception {
        when(service.getBySlug("synthetic-uni")).thenReturn(new UniversityDetailResponse("synthetic-uni",
                "Synthetic University", "Testville", "XA", "Synthetic Land A", "https://example.invalid/x",
                "desc", List.of()));

        mockMvc.perform(get("/api/v1/universities/synthetic-uni"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Synthetic University"))
                .andExpect(jsonPath("$.programs").isArray());
    }

    @Test
    void unknownSlugReturns404ErrorBody() throws Exception {
        when(service.getBySlug("nope")).thenThrow(new ResourceNotFoundException("University", "nope"));

        mockMvc.perform(get("/api/v1/universities/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("University not found: nope"));
    }

    @Test
    void malformedSlugReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/universities/Bad_Slug"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].field").value("slug"));
        verifyNoInteractions(service);
    }

    @Test
    void unknownPathReturns404NotServerError() throws Exception {
        mockMvc.perform(get("/api/v1/nothing-here"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}
