package com.pathwayai.backend.program.importer;

import static org.assertj.core.api.Assertions.assertThat;

import com.pathwayai.backend.program.entity.DegreeLevel;
import com.pathwayai.backend.program.entity.VerificationStatus;
import com.pathwayai.backend.program.importer.ReferenceData.ProgramData;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * Guards the SHIPPED reference data (no database needed). Every real record must be consistent, sourced and
 * honest; these rules are what stop a careless edit from publishing unverified facts.
 */
class ReferenceDataFileTest {

    static ReferenceData data;

    @BeforeAll
    static void load() throws IOException {
        data = ReferenceDataImporter.parse(new ClassPathResource("reference-data/programs.json").getInputStream());
    }

    @Test
    void shippedFileIsValid() {
        assertThat(ReferenceDataValidator.validate(data)).isEmpty();
    }

    @Test
    void containsAMixOfLevelsDisciplinesInstitutionsAndCountries() {
        assertThat(data.programs()).hasSizeBetween(6, 12);
        assertThat(data.programs().stream().map(ProgramData::degreeLevel).collect(Collectors.toSet()))
                .contains(DegreeLevel.BACHELOR, DegreeLevel.MASTER);
        assertThat(data.programs().stream().map(ProgramData::field).collect(Collectors.toSet()).size())
                .isGreaterThanOrEqualTo(3);
        assertThat(data.programs().stream().map(ProgramData::university).collect(Collectors.toSet()).size())
                .isGreaterThanOrEqualTo(2);
        assertThat(data.universities().stream().map(ReferenceData.UniversityData::country)
                .collect(Collectors.toSet()).size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void everyProgramIsVerifiedWithASourceAndADate() {
        for (ProgramData p : data.programs()) {
            assertThat(p.verification()).as(p.slug()).isNotNull();
            assertThat(p.verification().status()).as(p.slug()).isEqualTo(VerificationStatus.VERIFIED);
            assertThat(p.verification().verifiedOn()).as(p.slug()).isNotNull();
            assertThat(p.sources()).as(p.slug()).isNotEmpty();
            assertThat(p.officialProgramUrl()).as(p.slug()).isNotNull();
        }
    }

    @Test
    void applicationLinksAreOfficialAndBackedByTheProgramSources() {
        for (ProgramData p : data.programs()) {
            if (p.officialApplicationUrl() != null) {
                assertThat(p.sources()).as(p.slug() + " application URL must be a cited source")
                        .contains(p.officialApplicationUrl());
            }
        }
    }

    @Test
    void everyFactCitesASourceListedInTheFile() {
        Set<String> urls = data.sources().stream().map(ReferenceData.SourceData::url).collect(Collectors.toSet());
        for (ProgramData p : data.programs()) {
            List<String> cited = new java.util.ArrayList<>(p.sources());
            p.intakes().forEach(i -> cited.add(i.source()));
            p.tuition().forEach(t -> cited.add(t.source()));
            p.requirements().forEach(r -> cited.add(r.source()));
            assertThat(urls).as(p.slug()).containsAll(cited);
        }
    }

    @Test
    void sourcesAreRealHttpsPagesNotPlaceholders() {
        for (var s : data.sources()) {
            assertThat(s.url()).startsWith("https://");
            assertThat(s.url()).doesNotContain("example", "invalid", "localhost", "test");
            assertThat(s.retrievedOn()).isNotNull();
        }
        for (var u : data.universities()) {
            if (u.websiteUrl() != null) {
                assertThat(u.websiteUrl()).startsWith("https://").doesNotContain("example", "invalid");
            }
        }
    }

    @Test
    void tuitionIsNeverInventedAsZero() {
        for (ProgramData p : data.programs()) {
            p.tuition().forEach(t -> assertThat(t.amount().signum()).as(p.slug()).isPositive());
        }
    }
}
