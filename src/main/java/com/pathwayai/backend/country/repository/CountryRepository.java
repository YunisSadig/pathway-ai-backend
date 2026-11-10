package com.pathwayai.backend.country.repository;

import com.pathwayai.backend.country.entity.Country;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CountryRepository extends JpaRepository<Country, Long> {

    Optional<Country> findByIso2Code(String iso2Code);
}
