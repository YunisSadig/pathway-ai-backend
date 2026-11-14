package com.pathwayai.backend.account;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Beans the account services need in every run mode (web application and command-line jobs alike). */
@Configuration
@EnableConfigurationProperties(AccountProperties.class)
class AccountBeansConfig {

    @Bean
    PasswordEncoder passwordEncoder(AccountProperties props) {
        return new BCryptPasswordEncoder(props.bcryptCost());
    }
}
