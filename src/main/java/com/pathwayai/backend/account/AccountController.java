package com.pathwayai.backend.account;

import com.pathwayai.backend.account.AccountDtos.AccountResponse;
import com.pathwayai.backend.account.AccountDtos.ChangePasswordRequest;
import com.pathwayai.backend.account.AccountDtos.ConfigResponse;
import com.pathwayai.backend.account.AccountDtos.EmailRequest;
import com.pathwayai.backend.account.AccountDtos.LoginRequest;
import com.pathwayai.backend.account.AccountDtos.MessageResponse;
import com.pathwayai.backend.account.AccountDtos.PasswordConfirmation;
import com.pathwayai.backend.account.AccountDtos.RegisterRequest;
import com.pathwayai.backend.account.AccountDtos.RegisterResponse;
import com.pathwayai.backend.account.AccountDtos.ResetPasswordRequest;
import com.pathwayai.backend.account.AccountDtos.TokenRequest;
import com.pathwayai.backend.account.ProfileDtos.DisplayNameRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Sign up, log in and out, verification, password reset, and the signed-in account's own data. */
@RestController
@RequestMapping("/api/v1/account")
class AccountController {

    private static final MessageResponse CHECK_EMAIL = new MessageResponse(
            "If that address can be used, we have sent you an email with the next step.");

    private final AccountService service;
    private final ProfileService profiles;
    private final SavedService saved;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    AccountController(AccountService service, ProfileService profiles, SavedService saved) {
        this.service = service;
        this.profiles = profiles;
        this.saved = saved;
    }

    @GetMapping("/config")
    ConfigResponse config() {
        return new ConfigResponse(service.registrationOpen(), service.emailDeliveryAvailable(), service.cataloguePublic());
    }

    @PostMapping("/register")
    ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest body, HttpServletRequest request,
                                              HttpServletResponse response) {
        var result = service.register(body.email(), body.password(), body.displayName(), ClientIp.of(request));
        if (result.account().isEmpty()) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(new RegisterResponse("CHECK_EMAIL", CHECK_EMAIL.message(), null));
        }
        AccountRow account = result.account().get();
        startSession(account, request, response);
        return ResponseEntity.status(HttpStatus.CREATED).body(new RegisterResponse("CREATED", "Your account is ready.", view(account)));
    }

    @PostMapping("/login")
    AccountResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        AccountRow account = service.authenticate(body.email(), body.password(), ClientIp.of(request));
        startSession(account, request, response);
        return view(account);
    }

    @GetMapping("/me")
    AccountResponse me(Authentication authentication) {
        return view(current(authentication));
    }

    @PatchMapping("/me")
    AccountResponse updateMe(@Valid @RequestBody DisplayNameRequest body, Authentication authentication) {
        UUID id = CurrentAccount.id(authentication);
        service.updateDisplayName(id, body.displayName());
        return view(current(authentication));
    }

    @PostMapping("/verify-email")
    MessageResponse verifyEmail(@Valid @RequestBody TokenRequest body) {
        service.verifyEmail(body.token());
        return new MessageResponse("Your email address is confirmed.");
    }

    @PostMapping("/resend-verification")
    ResponseEntity<MessageResponse> resend(@Valid @RequestBody EmailRequest body, HttpServletRequest request) {
        service.resendVerification(body.email(), ClientIp.of(request));
        return ResponseEntity.accepted().body(CHECK_EMAIL);
    }

    @PostMapping("/forgot-password")
    ResponseEntity<MessageResponse> forgot(@Valid @RequestBody EmailRequest body, HttpServletRequest request) {
        service.forgotPassword(body.email(), ClientIp.of(request));
        return ResponseEntity.accepted().body(CHECK_EMAIL);
    }

    @PostMapping("/reset-password")
    MessageResponse reset(@Valid @RequestBody ResetPasswordRequest body) {
        service.resetPassword(body.token(), body.newPassword());
        return new MessageResponse("Your password has been changed. You can log in now.");
    }

    @PostMapping("/change-password")
    MessageResponse change(@Valid @RequestBody ChangePasswordRequest body, Authentication authentication, HttpServletRequest request) {
        var session = request.getSession(false);
        service.changePassword(CurrentAccount.id(authentication), body.currentPassword(), body.newPassword(),
                session == null ? null : session.getId());
        return new MessageResponse("Your password has been changed. Other devices were logged out.");
    }

    @PostMapping("/logout-all")
    ResponseEntity<Void> logoutAll(Authentication authentication, HttpServletRequest request) {
        service.terminateSessions(CurrentAccount.id(authentication), request.getSession(false) == null ? null : request.getSession(false).getId());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    ResponseEntity<Void> delete(@Valid @RequestBody PasswordConfirmation body, Authentication authentication, HttpServletRequest request,
                                HttpServletResponse response) {
        service.deleteAccount(CurrentAccount.id(authentication), body.password());
        var session = request.getSession(false);
        if (session != null) {
            try {
                session.invalidate();
            } catch (IllegalStateException alreadyGone) {
                // the session was ended together with the account
            }
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    /** Everything the service holds about the signed-in student, as one JSON document (GDPR access and portability). */
    @GetMapping("/export")
    Map<String, Object> export(Authentication authentication) {
        UUID id = CurrentAccount.id(authentication);
        return Map.of("exportedFor", view(current(authentication)), "profile", profiles.get(id), "saved", saved.list(id));
    }

    private AccountRow current(Authentication authentication) {
        return service.find(CurrentAccount.id(authentication))
                .orElseThrow(() -> new AccountException(401, "UNAUTHENTICATED", "Please log in again."));
    }

    private void startSession(AccountRow account, HttpServletRequest request, HttpServletResponse response) {
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

    static AccountResponse view(AccountRow a) {
        return new AccountResponse(a.id(), a.email(), a.displayName(), a.role(), a.emailVerified(), a.catalogueAccess(), a.createdAt());
    }
}
