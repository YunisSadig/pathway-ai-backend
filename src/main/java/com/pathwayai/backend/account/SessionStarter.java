package com.pathwayai.backend.account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/** Opens the one kind of session this application has, for password login and Google sign-in alike. */
@Component
class SessionStarter {

    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    void start(AccountRow account, HttpServletRequest request, HttpServletResponse response) {
        if (request.getSession(false) != null) {
            request.changeSessionId(); // a new session id after login defeats session fixation
        } else {
            request.getSession(true);
        }
        var authority = new SimpleGrantedAuthority("ADMIN".equals(account.role()) ? "ROLE_ADMIN" : "ROLE_USER");
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(account.id().toString(), null, List.of(authority)));
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
    }
}
