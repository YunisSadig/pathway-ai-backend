package com.pathwayai.backend.provider.studyinfo;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The reviewed official-classification mapping is internally consistent and only targets fields that exist in the taxonomy. */
class FieldTaxonomyFileTest {

    @Test
    @SuppressWarnings("unchecked")
    void everyMappedSlugIsAKnownAcademicFieldAndEveryEntryCarriesItsOfficialLabel() throws Exception {
        Set<String> known = new HashSet<>();
        try (InputStream in = getClass().getResourceAsStream("/reference-data/taxonomy.json")) {
            for (Object f : (List<Object>) JsonMapper.builder().build().readValue(in, Map.class).get("fields")) {
                known.add((String) ((Map<String, Object>) f).get("slug"));
            }
        }
        try (InputStream in = getClass().getResourceAsStream("/reference-data/studyinfo-field-map.json")) {
            List<Object> mappings = (List<Object>) JsonMapper.builder().build().readValue(in, Map.class).get("mappings");
            assertThat(mappings).hasSizeGreaterThan(20);
            for (Object o : mappings) {
                Map<String, Object> m = (Map<String, Object>) o;
                assertThat(m.get("code").toString()).matches("\\d{3,4}");
                assertThat(m.get("label").toString()).isNotBlank().isEqualTo(m.get("label").toString().trim());
                assertThat(known).as("slug of code " + m.get("code")).contains((String) m.get("slug"));
            }
        }
    }

    @Test
    void detailedCodesOverrideTheirNarrowFieldAndUnlistedCodesYieldNothing() {
        FieldTaxonomy t = new FieldTaxonomy();
        assertThat(t.lookup("0311")).get().extracting(FieldTaxonomy.Entry::slug).isEqualTo("economics");
        assertThat(t.lookup("031")).get().extracting(FieldTaxonomy.Entry::slug).isEqualTo("social-sciences");
        assertThat(t.lookup("0731")).get().extracting(FieldTaxonomy.Entry::slug).isEqualTo("architecture-and-design");
        assertThat(t.lookup("041")).get().extracting(FieldTaxonomy.Entry::slug).isEqualTo("business-and-administration");
        assertThat(t.lookup("053")).get().extracting(FieldTaxonomy.Entry::slug).isEqualTo("natural-sciences");
        assertThat(t.lookup("032")).isEmpty(); // journalism: no reviewed mapping, so no field rather than a guess
        assertThat(t.lookup("0000")).isEmpty();
    }
}
