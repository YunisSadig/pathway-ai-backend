package com.pathwayai.backend.account;

import com.pathwayai.backend.account.StudentProfileDtos.DestinationCountry;
import com.pathwayai.backend.account.StudentProfileDtos.DestinationUniversity;
import com.pathwayai.backend.account.StudentProfileDtos.Destinations;
import com.pathwayai.backend.program.dto.ProgramListItemResponse;
import com.pathwayai.backend.program.service.ProgramSearchCriteria;
import com.pathwayai.backend.program.service.ProgramService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * Which destinations and universities a student can choose. They come from the live catalogue through {@link ProgramService}, i.e. through
 * the SAME discovery rules as the public API: only programs that are verified and kept current by a working official-source connector count.
 * Nothing is hard-coded, so a new connector adds its country here by itself. An account that may not see the protected catalogue receives
 * nothing from it (not even a country name): the endpoint answers {@code restricted = true}.
 */
@Service
class DestinationService {

    private static final int PAGE = 100;
    private static final int MAX_PAGES = 20;

    private final ProgramService programs;
    private final AccountService accounts;

    DestinationService(ProgramService programs, AccountService accounts) {
        this.programs = programs;
        this.accounts = accounts;
    }

    Destinations forAccount(UUID accountId) {
        if (!accounts.canViewCatalogue(accountId)) {
            return new Destinations(true, List.of(), List.of());
        }
        Map<String, DestinationCountry> countries = new LinkedHashMap<>();
        Map<String, DestinationUniversity> universities = new LinkedHashMap<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            var result = programs.search(ProgramSearchCriteria.none(), PageRequest.of(page, PAGE, Sort.by("id")));
            for (ProgramListItemResponse p : result.content()) {
                var u = p.university();
                if (u == null || u.countryCode() == null) {
                    continue;
                }
                countries.putIfAbsent(u.countryCode(), new DestinationCountry(u.countryCode(), u.countryName()));
                universities.putIfAbsent(u.slug(), new DestinationUniversity(u.slug(), u.name(), u.countryCode()));
            }
            if (page + 1 >= result.totalPages()) {
                break;
            }
        }
        return new Destinations(false, countries.values().stream().sorted((a, b) -> a.name().compareToIgnoreCase(b.name())).toList(),
                universities.values().stream().sorted((a, b) -> a.name().compareToIgnoreCase(b.name())).toList());
    }
}
