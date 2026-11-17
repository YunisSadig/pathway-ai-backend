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
    @Autowired(required=false) private com.pathwayai.backend.program.service.CuratedCatalog curated;
    @Autowired(required=false) private com.pathwayai.backend.program.repository.DegreeProgramRepository programRepository;

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
                .and(discovery.universities())
                .and(curated==null ? (root,query,cb)->cb.conjunction() : curated.universities());
        return PageResponse.from(universityRepository.findAll(spec, pageable), mapper::toSummary);
    }

    /** One university by slug under the SAME listing rules as the search (a withheld or unverified institution is absent). Identity fields only. */
    public java.util.Optional<UniversitySummaryResponse> findListedBySlug(String slug) {
        Specification<University> spec = Specification
                .where(fetchCountry())
                .and((root, query, cb) -> cb.equal(root.get("slug"), slug))
                .and(discovery.universities());
        return universityRepository.findOne(spec).map(mapper::toSummary);
    }

    public UniversityDetailResponse getBySlug(String slug) {
        var detail=universityRepository.findBySlug(slug)
                .map(mapper::toDetail)
                .orElseThrow(() -> new ResourceNotFoundException("University", slug));
        if(curated==null || !curated.active() || !"FI".equals(detail.countryCode())) return detail;
        var identifiers=programRepository==null ? curated.selectedIdentifiers()
                : programRepository.findAll(com.pathwayai.backend.program.repository.ProgramSpecifications.fetchAssociations()
                    .and(com.pathwayai.backend.program.repository.ProgramSpecifications.atUniversity(slug))
                    .and(discovery.programs()).and(curated.programs())).stream().map(com.pathwayai.backend.program.entity.DegreeProgram::getIdentifier).toList();
        return new UniversityDetailResponse(detail.slug(),detail.name(),detail.city(),detail.countryCode(),detail.countryName(),
                detail.websiteUrl(),detail.description(),detail.programs().stream().filter(p->identifiers.contains(p.identifier())).toList());
    }
}
