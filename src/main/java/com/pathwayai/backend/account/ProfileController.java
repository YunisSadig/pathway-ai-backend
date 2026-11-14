package com.pathwayai.backend.account;

import com.pathwayai.backend.account.ProfileDtos.ProfileRequest;
import com.pathwayai.backend.account.ProfileDtos.ProfileResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in student's own profile. There is no id in the URL: the account comes from the session, so another student's profile cannot be addressed. */
@RestController
@RequestMapping("/api/v1/account/profile")
class ProfileController {

    private final ProfileService profiles;

    ProfileController(ProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping
    ProfileResponse get(Authentication authentication) {
        return profiles.get(CurrentAccount.id(authentication));
    }

    @PutMapping
    ProfileResponse replace(@Valid @RequestBody ProfileRequest body, Authentication authentication) {
        return profiles.replace(CurrentAccount.id(authentication), body);
    }

    @DeleteMapping
    ResponseEntity<Void> clear(Authentication authentication) {
        profiles.clear(CurrentAccount.id(authentication));
        return ResponseEntity.noContent().build();
    }
}
