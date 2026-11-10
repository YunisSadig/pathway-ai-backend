package com.pathwayai.backend.university.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.pathwayai.backend.university.entity.University;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

class UniversitySpecificationsTest {

    @Test
    void escapesLikeMetaCharacters() {
        assertThat(UniversitySpecifications.escapeLike("50%_off\\x")).isEqualTo("50\\%\\_off\\\\x");
    }

    @Test
    void blankInputsProduceNonNullMatchAllSpecifications() {
        for (Specification<University> spec : java.util.List.of(
                UniversitySpecifications.nameContains("  "),
                UniversitySpecifications.nameContains(null),
                UniversitySpecifications.inCountry(""),
                UniversitySpecifications.inCountry(null))) {
            assertThat(spec).isNotNull();
            CriteriaBuilder cb = mock(CriteriaBuilder.class);
            spec.toPredicate(mockRoot(), mock(CriteriaQuery.class), cb);
            verify(cb).conjunction();
            verifyNoMoreInteractions(cb);
        }
    }

    @SuppressWarnings("unchecked")
    private static Root<University> mockRoot() {
        return mock(Root.class);
    }
}
