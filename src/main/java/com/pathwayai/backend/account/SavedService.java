package com.pathwayai.backend.account;

import com.pathwayai.backend.common.exception.ResourceNotFoundException;
import com.pathwayai.backend.program.dto.ProgramDetailResponse;
import com.pathwayai.backend.program.service.ProgramService;
import com.pathwayai.backend.university.service.UniversityService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saved programs and universities of one account. A saved item stores only the public identifier; names and facts are read from the
 * catalogue when shown, through the same visibility rules as the public API (a withheld program is shown as unavailable, never leaked).
 */
@Service
class SavedService {

    record SavedProgram(String identifier, Instant savedAt, boolean available, String name, String universityName, String universitySlug,
                        String degreeLevel) {
    }

    record SavedUniversity(String slug, Instant savedAt, boolean available, String name, String countryName) {
    }

    record SavedResponse(List<SavedProgram> programs, List<SavedUniversity> universities, boolean catalogueVisible) {
    }

    private final JdbcClient jdbc;
    private final ProgramService programs;
    private final UniversityService universities;
    private final AccountService accounts;
    private final AccountProperties props;

    SavedService(JdbcClient jdbc, ProgramService programs, UniversityService universities, AccountService accounts, AccountProperties props) {
        this.jdbc = jdbc;
        this.programs = programs;
        this.universities = universities;
        this.accounts = accounts;
        this.props = props;
    }

    SavedResponse list(UUID accountId) {
        boolean visible = accounts.canViewCatalogue(accountId);
        List<SavedProgram> savedPrograms = jdbc.sql("select program_identifier, saved_at from saved_program where account_id = :id order by saved_at desc")
                .param("id", accountId).query((rs, n) -> new Object[] {rs.getString(1), rs.getTimestamp(2).toInstant()}).list().stream()
                .map(r -> resolveProgram((String) r[0], (Instant) r[1], visible)).toList();
        List<SavedUniversity> savedUniversities = jdbc.sql("select university_slug, saved_at from saved_university where account_id = :id order by saved_at desc")
                .param("id", accountId).query((rs, n) -> new Object[] {rs.getString(1), rs.getTimestamp(2).toInstant()}).list().stream()
                .map(r -> resolveUniversity((String) r[0], (Instant) r[1], visible)).toList();
        return new SavedResponse(savedPrograms, savedUniversities, visible);
    }

    @Transactional
    void saveProgram(UUID accountId, String identifier) {
        // the public program tier (name, level, university) is open to every account, so any account may save a program
        try {
            programs.getByIdentifier(identifier);
        } catch (ResourceNotFoundException e) {
            throw new AccountException(404, "NOT_FOUND", "This program is not available.");
        }
        enforceLimit("saved_program", accountId);
        jdbc.sql("insert into saved_program (account_id, program_identifier) values (:id, :p) on conflict do nothing")
                .param("id", accountId).param("p", identifier).update();
    }

    @Transactional
    void removeProgram(UUID accountId, String identifier) {
        jdbc.sql("delete from saved_program where account_id = :id and program_identifier = :p").param("id", accountId).param("p", identifier).update();
    }

    @Transactional
    void saveUniversity(UUID accountId, String slug) {
        // a university's identity is public, so any signed-in account may keep a shortlist of universities (programs still need catalogue access)
        if (universities.findListedBySlug(slug).isEmpty()) {
            throw new AccountException(404, "NOT_FOUND", "This university is not available.");
        }
        enforceLimit("saved_university", accountId);
        jdbc.sql("insert into saved_university (account_id, university_slug) values (:id, :s) on conflict do nothing")
                .param("id", accountId).param("s", slug).update();
    }

    @Transactional
    void removeUniversity(UUID accountId, String slug) {
        jdbc.sql("delete from saved_university where account_id = :id and university_slug = :s").param("id", accountId).param("s", slug).update();
    }

    private void enforceLimit(String table, UUID accountId) {
        int count = jdbc.sql("select count(*) from " + table + " where account_id = :id").param("id", accountId).query(Integer.class).single();
        if (count >= props.maxSavedItems()) {
            throw new AccountException(409, "LIMIT_REACHED", "You can save up to " + props.maxSavedItems() + " items of each kind.");
        }
    }

    private SavedProgram resolveProgram(String identifier, Instant savedAt, boolean visible) {
        try {
            ProgramDetailResponse p = programs.getByIdentifier(identifier);
            return new SavedProgram(identifier, savedAt, true, p.name(), p.university().name(), p.university().slug(), p.degreeLevel().name());
        } catch (ResourceNotFoundException e) {
            // falls through: no longer listed
        }
        return new SavedProgram(identifier, savedAt, false, null, null, null, null);
    }

    private SavedUniversity resolveUniversity(String slug, Instant savedAt, boolean visible) {
        // identity only (name, country): public information, shown to every account
        return universities.findListedBySlug(slug)
                .map(u -> new SavedUniversity(slug, savedAt, true, u.name(), u.countryName()))
                .orElseGet(() -> new SavedUniversity(slug, savedAt, false, null, null));
    }
}
