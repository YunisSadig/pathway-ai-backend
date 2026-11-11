package com.pathwayai.backend.university.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pathwayai.backend.TestcontainersConfiguration;
import com.pathwayai.backend.university.catalogue.UniversityCatalogue.CountryData;
import com.pathwayai.backend.university.catalogue.UniversityCatalogue.ExternalId;
import com.pathwayai.backend.university.catalogue.UniversityCatalogue.SourceData;
import com.pathwayai.backend.university.catalogue.UniversityCatalogue.UniversityData;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** The catalogue importer against PostgreSQL with synthetic institutions. @Transactional rolls back per test. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class UniversityCatalogueImporterIT {

    static final LocalDate DAY = LocalDate.of(2026, 1, 2);

    @Autowired UniversityCatalogueImporter importer;
    @Autowired JdbcTemplate jdbc;

    static UniversityData uni(String slug, String name, String status, String... aliases) {
        String site = "REGISTRY_ONLY".equals(status) ? null : "https://www." + slug + ".example/";
        List<SourceData> sources = site == null ? List.of()
                : List.of(new SourceData(site, "Home " + slug, "Synthetic", "UNIVERSITY_WEBSITE", DAY));
        return new UniversityData(slug, name, "XA", "Testburg", null, site, null, null,
                List.of(new ExternalId("SCHAC_HOME_ORG", slug + ".example")), List.of(aliases), status, DAY, sources);
    }

    static UniversityCatalogue of(UniversityData... us) {
        return new UniversityCatalogue(1, "2026-01-02", List.of(new CountryData("XA", "Synthland")), List.of(us));
    }

    int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    @Test
    void importsInstitutionsWithIdentifiersAliasesAndSources() {
        var report = importer.importCatalogue(of(uni("alpha-u", "Alpha University", "VERIFIED", "ALPHA UNIV")));
        assertThat(report.changes()).containsEntry("universitiesCreated", 1).containsEntry("externalIds", 1)
                .containsEntry("sourcesCreated", 1);
        assertThat(count("select count(*) from university where slug = 'alpha-u' and verification_status = 'VERIFIED'")).isEqualTo(1);
        assertThat(count("select count(*) from university_alias where alias = 'ALPHA UNIV'")).isEqualTo(1);
        assertThat(count("select count(*) from university_source")).isEqualTo(1);
    }

    @Test
    void aSecondRunOfTheSameFileChangesNothing() {
        var catalogue = of(uni("alpha-u", "Alpha University", "VERIFIED"), uni("beta-u", "Beta University", "REGISTRY_ONLY"));
        importer.importCatalogue(catalogue);
        int universities = count("select count(*) from university");
        var second = importer.importCatalogue(catalogue);
        assertThat(second.total()).isZero();
        assertThat(count("select count(*) from university")).isEqualTo(universities);
    }

    @Test
    void anInstitutionAlreadyPresentUnderADifferentSlugIsMatchedByExternalIdNotDuplicated() {
        importer.importCatalogue(of(uni("old-slug", "Alpha University", "VERIFIED")));
        // same SCHAC id, new slug and a renamed institution: still the same row
        var renamed = new UniversityData("new-slug", "Alpha Renamed University", "XA", null, null, "https://www.alpha.example/",
                null, null, List.of(new ExternalId("SCHAC_HOME_ORG", "old-slug.example")), List.of(), "VERIFIED", DAY,
                List.of(new SourceData("https://www.alpha.example/", "Home", "Synthetic", "UNIVERSITY_WEBSITE", DAY)));
        importer.importCatalogue(of(renamed));
        assertThat(count("select count(*) from university where slug in ('old-slug','new-slug')")).isEqualTo(1);
        assertThat(count("select count(*) from university where slug = 'old-slug' and name = 'Alpha University'")).isEqualTo(1);
    }

    @Test
    void anInstitutionAlreadyPresentUnderAnAliasInTheSameCountryIsNotDuplicated() {
        jdbc.update("insert into country (iso2_code, name) values ('XA', 'Synthland')");
        long country = jdbc.queryForObject("select id from country where iso2_code = 'XA'", Long.class);
        jdbc.update("insert into university (country_id, slug, name) values (?, 'legacy-alpha', 'Alpha University')", country);
        var u = new UniversityData("alpha-u", "Alpha University", "XA", "Testburg", null, null, null, null,
                List.of(new ExternalId("SCHAC_HOME_ORG", "alpha.example")), List.of("Alpha University"), "REGISTRY_ONLY", DAY, List.of());
        var report = importer.importCatalogue(of(u));
        assertThat(report.changes()).doesNotContainKey("universitiesCreated").containsEntry("universitiesUpdated", 1);
        assertThat(count("select count(*) from university")).isEqualTo(1);
        // identity is kept, empty columns are filled
        assertThat(jdbc.queryForObject("select slug from university", String.class)).isEqualTo("legacy-alpha");
        assertThat(jdbc.queryForObject("select city from university", String.class)).isEqualTo("Testburg");
    }

    @Test
    void existingCuratedValuesAreNeverOverwritten() {
        importer.importCatalogue(of(uni("alpha-u", "Alpha University", "VERIFIED")));
        jdbc.update("update university set city = 'Curated City' where slug = 'alpha-u'");
        importer.importCatalogue(of(uni("alpha-u", "Alpha University", "VERIFIED")));
        assertThat(jdbc.queryForObject("select city from university where slug = 'alpha-u'", String.class)).isEqualTo("Curated City");
    }

    @Test
    void verificationOnlyEverMovesUpSoATemporarilyUnreachableSiteDoesNotUnverify() {
        importer.importCatalogue(of(uni("alpha-u", "Alpha University", "VERIFIED")));
        var downgrade = new UniversityData("alpha-u", "Alpha University", "XA", null, null, null, null, null,
                List.of(new ExternalId("SCHAC_HOME_ORG", "alpha-u.example")), List.of(), "REGISTRY_ONLY", DAY.plusDays(30), List.of());
        importer.importCatalogue(of(downgrade));
        assertThat(jdbc.queryForObject("select verification_status from university where slug = 'alpha-u'", String.class)).isEqualTo("VERIFIED");
    }

    @Test
    void aRegistryOnlyInstitutionCanLaterBecomeVerified() {
        importer.importCatalogue(of(uni("alpha-u", "Alpha University", "REGISTRY_ONLY")));
        importer.importCatalogue(of(uni("alpha-u", "Alpha University", "VERIFIED")));
        assertThat(jdbc.queryForObject("select verification_status from university where slug = 'alpha-u'", String.class)).isEqualTo("VERIFIED");
        assertThat(jdbc.queryForObject("select website_url from university where slug = 'alpha-u'", String.class)).isEqualTo("https://www.alpha-u.example/");
    }

    @Test
    void anInvalidFileIsRejectedCompletelyAndWritesNothing() {
        var good = uni("alpha-u", "Alpha University", "VERIFIED");
        var bad = new UniversityData("bad_slug", "Bad", "XA", null, null, null, null, null, List.of(), List.of(), "VERIFIED", DAY, List.of());
        assertThatThrownBy(() -> importer.importCatalogue(of(good, bad)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nothing imported");
        assertThat(count("select count(*) from university")).isZero();
        assertThat(count("select count(*) from country where iso2_code = 'XA'")).isZero();
    }

    @Test
    void aConflictInTheDatabaseIsRefusedRatherThanGuessedAbout() {
        importer.importCatalogue(of(uni("alpha-u", "Alpha University", "VERIFIED"), uni("beta-u", "Beta University", "VERIFIED")));
        // a record whose slug is alpha's but whose external id belongs to beta is a data error
        var conflict = new UniversityData("alpha-u", "Alpha University", "XA", null, null, null, null, null,
                List.of(new ExternalId("SCHAC_HOME_ORG", "beta-u.example")), List.of(), "REGISTRY_ONLY", DAY, List.of());
        assertThatThrownBy(() -> importer.importCatalogue(of(conflict))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theDatabaseRejectsAWebsiteOnARegistryOnlyRow() {
        importer.importCatalogue(of(uni("alpha-u", "Alpha University", "REGISTRY_ONLY")));
        assertThatThrownBy(() -> jdbc.update("update university set website_url = 'https://x.example' where slug = 'alpha-u'"))
                .hasMessageContaining("ck_university_registry_only_no_website");
    }
}
