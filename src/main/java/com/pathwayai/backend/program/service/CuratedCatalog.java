package com.pathwayai.backend.program.service;

import com.pathwayai.backend.program.entity.DegreeProgram;
import com.pathwayai.backend.university.entity.University;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Curation controls discovery only. Detail URLs, saved identifiers and offering lifecycle remain independent. */
@Component
public class CuratedCatalog {
    private final JdbcClient jdbc;
    private final boolean enabled;

    public CuratedCatalog(JdbcClient jdbc, @Value("${app.curated.enabled:true}") boolean enabled) {
        this.jdbc=jdbc;
        this.enabled=enabled;
    }

    public boolean schemaExists() {
        return enabled && jdbc.sql("select to_regclass('public.catalog_release') is not null")
                .query(Boolean.class).single();
    }

    public boolean active() {
        return schemaExists() && jdbc.sql("select exists(select 1 from catalog_release where enabled and country_code='FI')")
                .query(Boolean.class).single();
    }

    public List<Long> selectedIds() {
        if (!active()) return List.of();
        return jdbc.sql("""
                select m.program_id from catalog_membership m join catalog_release r on r.id=m.release_id
                where r.enabled and r.country_code='FI' order by m.program_id""").query(Long.class).list();
    }

    public Specification<DegreeProgram> programs() {
        if (!active()) return (r,q,cb)->cb.conjunction();
        var selected=selectedIds();
        return (r,q,cb)->cb.or(cb.notEqual(r.get("university").get("country").get("iso2Code"),"FI"), r.get("id").in(selected));
    }

    public Specification<University> universities() {
        if (!active()) return (r,q,cb)->cb.conjunction();
        var ids=jdbc.sql("""
                select distinct p.university_id from degree_program p join catalog_membership m on m.program_id=p.id
                join catalog_release r on r.id=m.release_id where r.enabled and r.country_code='FI'""").query(Long.class).list();
        return (r,q,cb)->cb.or(cb.notEqual(r.get("country").get("iso2Code"),"FI"), r.get("id").in(ids));
    }

    public String membership(long programId) {
        if (!active()) return "UNRESTRICTED";
        return selectedIds().contains(programId) ? "CURATED" : "OUTSIDE_CURATED_CATALOG";
    }

    public List<String> selectedIdentifiers() {
        if(!active())return List.of();
        return jdbc.sql("""
                select u.slug||'--'||p.slug from degree_program p join university u on u.id=p.university_id
                join catalog_membership m on m.program_id=p.id join catalog_release r on r.id=m.release_id
                where r.enabled and r.country_code='FI'""").query(String.class).list();
    }
}
