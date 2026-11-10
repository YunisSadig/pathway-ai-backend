package com.pathwayai.backend.university.service;

import static com.pathwayai.backend.support.SyntheticFixtures.countryA;
import static com.pathwayai.backend.support.SyntheticFixtures.program;
import static com.pathwayai.backend.support.SyntheticFixtures.university;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pathwayai.backend.common.exception.ResourceNotFoundException;
import com.pathwayai.backend.common.response.PageResponse;
import com.pathwayai.backend.university.dto.UniversityDetailResponse;
import com.pathwayai.backend.university.dto.UniversitySummaryResponse;
import com.pathwayai.backend.university.entity.University;
import com.pathwayai.backend.university.mapper.UniversityMapper;
import com.pathwayai.backend.university.repository.UniversityRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

class UniversityServiceTest {

    private final UniversityRepository repository = mock(UniversityRepository.class);
    private final UniversityService service = new UniversityService(repository, new UniversityMapper());

    @Test
    @SuppressWarnings("unchecked")
    void searchMapsEntitiesToSummaryDtosAndKeepsPagingInfo() {
        University uni = university(countryA(), "synthetic-uni", "Synthetic University", "Testville");
        Pageable pageable = PageRequest.of(1, 5);
        Page<University> page = new PageImpl<>(List.of(uni), pageable, 6);
        when(repository.findAll(any(Specification.class), eq(pageable))).thenReturn(page);

        PageResponse<UniversitySummaryResponse> result = service.search("synth", "xa", pageable);

        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(5);
        assertThat(result.totalElements()).isEqualTo(6);
        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.content()).singleElement().satisfies(dto -> {
            assertThat(dto.slug()).isEqualTo("synthetic-uni");
            assertThat(dto.countryCode()).isEqualTo("XA");
            assertThat(dto.countryName()).isEqualTo("Synthetic Land A");
        });
    }

    @Test
    void getBySlugReturnsDetailWithProgramsSortedByName() {
        University uni = university(countryA(), "synthetic-uni", "Synthetic University", "Testville");
        uni.getPrograms().add(program(uni, "zeta", "Zeta Program"));
        uni.getPrograms().add(program(uni, "alpha", "Alpha Program"));
        when(repository.findBySlug("synthetic-uni")).thenReturn(Optional.of(uni));

        UniversityDetailResponse detail = service.getBySlug("synthetic-uni");

        assertThat(detail.programs()).extracting("name").containsExactly("Alpha Program", "Zeta Program");
        verify(repository).findBySlug("synthetic-uni");
    }

    @Test
    void getBySlugThrowsWhenMissing() {
        when(repository.findBySlug("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getBySlug("missing"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("missing");
    }
}
