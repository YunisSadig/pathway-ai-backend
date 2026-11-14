package com.pathwayai.backend.university.service;

import com.pathwayai.backend.common.discovery.DiscoveryPolicy;
import static com.pathwayai.backend.university.repository.UniversitySpecifications.fetchCountry;
import static com.pathwayai.backend.university.repository.UniversitySpecifications.inCountry;
import static com.pathwayai.backend.university.repository.UniversitySpecifications.nameContains;

import com.pathwayai.backend.common.exception.ResourceNotFoundException;
import com.pathwayai.backend.common.response.PageResponse;
import com.pathwayai.backend.university.dto.UniversityDetailResponse;
import com.pathwayai.backend.university.dto.UniversitySummaryResponse;
import com.pathwayai.backend.university.entity.University;
import com.pathwayai.backend.university.mapper.UniversityMapper;
import com.pathwayai.backend.university.repository.UniversityRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UniversityService {

    private final UniversityRepository universityRepository;
    private final UniversityMapper mapper;

    private final DiscoveryPolicy discovery;

    /** Without a discovery restriction (hand-built services in unit tests). */
    public UniversityService(UniversityRepository universityRepository, UniversityMapper mapper) {
        this(universityRepository, mapper, DiscoveryPolicy.openToAll());
    }

    @Autowired
    public UniversityService(UniversityRepository universityRepository, UniversityMapper mapper, DiscoveryPolicy discovery) {
        this.universityRepository = universityRepository;
        this.mapper = mapper;
        this.discovery = discovery;
    }

    public PageResponse<UniversitySummaryResponse> search(String nameQuery, String countryCode, Pageable pageable) {
        Specification<University> spec = Specification
                .where(fetchCountry())
                .and(nameContains(nameQuery))
                .and(inCountry(countryCode))
                .and(discovery.universities());
        return PageResponse.from(universityRepository.findAll(spec, pageable), mapper::toSummary);
    }

    public UniversityDetailResponse getBySlug(String slug) {
        return universityRepository.findBySlug(slug)
                .map(mapper::toDetail)
                .orElseThrow(() -> new ResourceNotFoundException("University", slug));
    }
}
