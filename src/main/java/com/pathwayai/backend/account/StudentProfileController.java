package com.pathwayai.backend.account;

import com.pathwayai.backend.account.StudentProfileDtos.AvatarInfo;
import com.pathwayai.backend.account.StudentProfileDtos.Destinations;
import com.pathwayai.backend.account.StudentProfileDtos.Options;
import com.pathwayai.backend.account.StudentProfileDtos.StudentProfile;
import com.pathwayai.backend.account.StudentProfileDtos.StudentProfileView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in student's own profile document, options, destinations and picture. There is no account id in any URL: every call acts on the
 * session's account, so another student's data cannot be addressed.
 */
@RestController
@RequestMapping("/api/v1/account")
class StudentProfileController {

    record LanguageRequest(@NotNull @Pattern(regexp = "^(en|ru)$") String language) {
    }

    private final StudentProfileService profiles;
    private final DestinationService destinations;
    private final AvatarService avatars;

    StudentProfileController(StudentProfileService profiles, DestinationService destinations, AvatarService avatars) {
        this.profiles = profiles;
        this.destinations = destinations;
        this.avatars = avatars;
    }

    @GetMapping("/student-profile")
    StudentProfileView get(Authentication authentication) {
        return profiles.get(CurrentAccount.id(authentication));
    }

    @PutMapping("/student-profile")
    StudentProfileView save(@Valid @RequestBody StudentProfile body, Authentication authentication) {
        return profiles.save(CurrentAccount.id(authentication), body);
    }

    @PostMapping("/student-profile/complete")
    StudentProfileView complete(Authentication authentication) {
        return profiles.complete(CurrentAccount.id(authentication));
    }

    @PutMapping("/language")
    ResponseEntity<Void> language(@Valid @RequestBody LanguageRequest body, Authentication authentication) {
        profiles.setLanguage(CurrentAccount.id(authentication), body.language());
        return ResponseEntity.noContent().build();
    }

    /** Fixed reference data (supported countries, grading scales, currencies, study areas, skills). Contains nothing about any student. */
    @GetMapping("/options")
    Options options() {
        return StudentProfileRules.options();
    }

    @GetMapping("/options/destinations")
    Destinations destinations(Authentication authentication) {
        return destinations.forAccount(CurrentAccount.id(authentication));
    }

    @GetMapping("/avatar")
    ResponseEntity<byte[]> avatar(Authentication authentication) {
        return avatars.read(CurrentAccount.id(authentication))
                .map(bytes -> ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(CacheControl.noStore())
                        .header("X-Content-Type-Options", "nosniff").body(bytes))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping(value = "/avatar", consumes = {"image/jpeg", "image/png", "application/octet-stream"})
    AvatarInfo uploadAvatar(@RequestBody byte[] body, Authentication authentication) {
        return avatars.replace(CurrentAccount.id(authentication), body);
    }

    @DeleteMapping("/avatar")
    ResponseEntity<Void> removeAvatar(Authentication authentication) {
        avatars.remove(CurrentAccount.id(authentication));
        return ResponseEntity.noContent().build();
    }
}
