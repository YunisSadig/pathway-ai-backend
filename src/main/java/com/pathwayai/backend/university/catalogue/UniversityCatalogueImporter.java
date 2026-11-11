package com.pathwayai.backend.university.catalogue;

import com.pathwayai.backend.university.catalogue.UniversityCatalogue.CountryData;
import com.pathwayai.backend.university.catalogue.UniversityCatalogue.ExternalId;
import com.pathwayai.backend.university.catalogue.UniversityCatalogue.SourceData;
import com.pathwayai.backend.university.catalogue.UniversityCatalogue.UniversityData;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent, additive import of institution identity. Resolution order for each record, so an institution that
 * is already present is updated, never duplicated:
 * <ol>
 *   <li>a stable external identifier (SCHAC home organisation, Erasmus code, ...) already owned by a university;</li>
 *   <li>the slug;</li>
 *   <li>the name or an alias, within the same country.</li>
 * </ol>
 * An existing row keeps its slug, name, city and website (identity is never rewritten); only empty columns are
 * filled, and verification only ever moves up (UNVERIFIED, REGISTRY_ONLY, VERIFIED), so a temporarily unreachable
 * website never un-verifies an institution. The whole file is validated first and applied in one transaction.
 * Nothing is ever deleted.
 */
@Service
public class UniversityCatalogueImporter {

    private final JdbcClient jdbc;

    public UniversityCatalogueImporter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Rows created or changed by one run. A repeat run of the same file reports all zeros. */
    public record Report(Map<String, Integer> changes) {
        public int total() {
            return changes.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    public Report importFrom(InputStream in) {
        return importCatalogue(UniversityCatalogue.parse(in));
    }

    @Transactional
    public Report importCatalogue(UniversityCatalogue catalogue) {
        List<String> errors = UniversityCatalogueValidator.validate(catalogue);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Catalogue rejected, nothing imported:\n - " + String.join("\n - ", errors));
        }
        Map<String, Integer> changes = new LinkedHashMap<>();
        for (CountryData c : catalogue.countries()) {
            ensureCountry(c, changes);
        }
        for (UniversityData u : catalogue.universities()) {
            importUniversity(u, changes);
        }
        return new Report(changes);
    }

    private void ensureCountry(CountryData c, Map<String, Integer> changes) {
        boolean exists = jdbc.sql("select count(*) from country where iso2_code = :iso")
                .param("iso", c.iso2()).query(Integer.class).single() > 0;
        if (!exists) {
            jdbc.sql("insert into country (iso2_code, name) values (:iso, :name)")
                    .param("iso", c.iso2()).param("name", c.name()).update();
            bump(changes, "countries");
        }
    }

    private void importUniversity(UniversityData u, Map<String, Integer> changes) {
        long countryId = jdbc.sql("select id from country where iso2_code = :iso")
                .param("iso", u.country()).query(Long.class).single();
        Long id = resolve(u, countryId);
        if (id == null) {
            id = jdbc.sql("""
                    insert into university (country_id, slug, name, city, website_url, native_name,
                                            admissions_url, programme_catalogue_url, verification_status, verified_on)
                    values (:country, :slug, :name, :city, :web, :native, :adm, :cat, :status, :on)
                    returning id""")
                    .param("country", countryId).param("slug", u.slug()).param("name", u.name())
                    .param("city", u.city()).param("web", u.websiteUrl()).param("native", u.nativeName())
                    .param("adm", u.admissionsUrl()).param("cat", u.programmeCatalogueUrl())
                    .param("status", u.verificationStatus()).param("on", u.verifiedOn())
                    .query(Long.class).single();
            bump(changes, "universitiesCreated");
        } else if (fillGaps(id, u)) {
            bump(changes, "universitiesUpdated");
        }
        for (ExternalId e : u.externalIdsOrEmpty()) {
            Optional<Long> owner = jdbc.sql("select university_id from university_external_id where scheme = :s and value = :v")
                    .param("s", e.scheme()).param("v", e.value()).query(Long.class).optional();
            if (owner.isEmpty()) {
                jdbc.sql("insert into university_external_id (university_id, scheme, value) values (:u, :s, :v)")
                        .param("u", id).param("s", e.scheme()).param("v", e.value()).update();
                bump(changes, "externalIds");
            }
        }
        Set<String> aliases = new LinkedHashSet<>(u.aliasesOrEmpty());
        if (u.nativeName() != null) {
            aliases.add(u.nativeName());
        }
        for (String alias : aliases) {
            int n = jdbc.sql("""
                    insert into university_alias (university_id, alias) values (:u, :a)
                    on conflict (university_id, alias) do nothing""")
                    .param("u", id).param("a", alias).update();
            if (n > 0) {
                bump(changes, "aliases");
            }
        }
        for (SourceData s : u.sourcesOrEmpty()) {
            long sourceId = upsertSource(s, changes);
            int n = jdbc.sql("""
                    insert into university_source (university_id, source_id) values (:u, :s)
                    on conflict do nothing""").param("u", id).param("s", sourceId).update();
            if (n > 0) {
                bump(changes, "universitySources");
            }
        }
    }

    /** Finds the existing university for a record by external id, then slug, then name/alias in the same country. */
    private Long resolve(UniversityData u, long countryId) {
        Set<Long> owners = new LinkedHashSet<>();
        for (ExternalId e : u.externalIdsOrEmpty()) {
            jdbc.sql("select university_id from university_external_id where scheme = :s and value = :v")
                    .param("s", e.scheme()).param("v", e.value()).query(Long.class).optional().ifPresent(owners::add);
        }
        if (owners.size() > 1) {
            throw new IllegalStateException("External ids of " + u.slug() + " belong to different universities " + owners);
        }
        Optional<Long> bySlug = jdbc.sql("select id from university where slug = :slug")
                .param("slug", u.slug()).query(Long.class).optional();
        if (!owners.isEmpty()) {
            Long owner = owners.iterator().next();
            if (bySlug.isPresent() && !bySlug.get().equals(owner)) {
                throw new IllegalStateException("Slug " + u.slug() + " and its external ids identify different universities");
            }
            return owner;
        }
        if (bySlug.isPresent()) {
            return bySlug.get();
        }
        Set<String> names = new LinkedHashSet<>();
        names.add(u.name().toLowerCase(Locale.ROOT));
        u.aliasesOrEmpty().forEach(a -> names.add(a.toLowerCase(Locale.ROOT)));
        if (u.nativeName() != null) {
            names.add(u.nativeName().toLowerCase(Locale.ROOT));
        }
        List<Long> byName = jdbc.sql("""
                select distinct u.id from university u left join university_alias a on a.university_id = u.id
                where u.country_id = :country and (lower(u.name) in (:names) or lower(a.alias) in (:names))""")
                .param("country", countryId).param("names", names).query(Long.class).list();
        if (byName.size() > 1) {
            throw new IllegalStateException("Name/aliases of " + u.slug() + " match several universities " + byName);
        }
        return byName.isEmpty() ? null : byName.get(0);
    }

    /** Fills empty columns of an existing row and raises (never lowers) its verification. Returns true if changed. */
    private boolean fillGaps(long id, UniversityData u) {
        Map<String, Object> row = jdbc.sql("""
                select city, website_url, native_name, admissions_url, programme_catalogue_url,
                       verification_status, verified_on from university where id = :id""")
                .param("id", id).query().singleRow();
        String city = (String) row.get("city");
        String web = (String) row.get("website_url");
        String nativeName = (String) row.get("native_name");
        String adm = (String) row.get("admissions_url");
        String cat = (String) row.get("programme_catalogue_url");
        String status = (String) row.get("verification_status");
        LocalDate verifiedOn = row.get("verified_on") == null ? null : ((java.sql.Date) row.get("verified_on")).toLocalDate();

        String newCity = city != null ? city : u.city();
        String newWeb = web != null ? web : u.websiteUrl();
        String newNative = nativeName != null ? nativeName : u.nativeName();
        String newAdm = adm != null ? adm : u.admissionsUrl();
        String newCat = cat != null ? cat : u.programmeCatalogueUrl();
        String newStatus = status;
        LocalDate newOn = verifiedOn;
        if (rank(u.verificationStatus()) > rank(status)
                && !(UniversityCatalogueValidator.REGISTRY_ONLY.equals(u.verificationStatus()) && newWeb != null)) {
            newStatus = u.verificationStatus();
            newOn = u.verifiedOn();
        } else if (rank(u.verificationStatus()) == rank(status) && u.verifiedOn() != null
                && (verifiedOn == null || u.verifiedOn().isAfter(verifiedOn))) {
            newOn = u.verifiedOn();
        }
        boolean changed = !java.util.Objects.equals(city, newCity) || !java.util.Objects.equals(web, newWeb)
                || !java.util.Objects.equals(nativeName, newNative) || !java.util.Objects.equals(adm, newAdm)
                || !java.util.Objects.equals(cat, newCat) || !java.util.Objects.equals(status, newStatus)
                || !java.util.Objects.equals(verifiedOn, newOn);
        if (changed) {
            jdbc.sql("""
                    update university set city = :city, website_url = :web, native_name = :native,
                           admissions_url = :adm, programme_catalogue_url = :cat,
                           verification_status = :status, verified_on = :on, updated_at = now()
                    where id = :id""")
                    .param("city", newCity).param("web", newWeb).param("native", newNative).param("adm", newAdm)
                    .param("cat", newCat).param("status", newStatus).param("on", newOn).param("id", id).update();
        }
        return changed;
    }

    private static int rank(String status) {
        return switch (status) {
            case "VERIFIED" -> 2;
            case "REGISTRY_ONLY" -> 1;
            default -> 0;
        };
    }

    /** Sources are keyed by URL. An existing source keeps its title; only a newer retrieval date is recorded. */
    private long upsertSource(SourceData s, Map<String, Integer> changes) {
        Optional<Map<String, Object>> existing = jdbc.sql("select id, retrieved_on from source where url = :url")
                .param("url", s.url()).query().listOfRows().stream().findFirst();
        if (existing.isEmpty()) {
            long id = jdbc.sql("""
                    insert into source (url, title, publisher, source_type, retrieved_on)
                    values (:url, :title, :pub, :type, :on) returning id""")
                    .param("url", s.url()).param("title", truncate(s.title(), 300)).param("pub", truncate(s.publisher(), 200))
                    .param("type", s.type()).param("on", s.retrievedOn()).query(Long.class).single();
            bump(changes, "sourcesCreated");
            return id;
        }
        long id = ((Number) existing.get().get("id")).longValue();
        LocalDate current = ((java.sql.Date) existing.get().get("retrieved_on")).toLocalDate();
        if (s.retrievedOn().isAfter(current)) {
            jdbc.sql("update source set retrieved_on = :on where id = :id").param("on", s.retrievedOn()).param("id", id).update();
            bump(changes, "sourcesRefreshed");
        }
        return id;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static void bump(Map<String, Integer> changes, String key) {
        changes.merge(key, 1, Integer::sum);
    }
}
