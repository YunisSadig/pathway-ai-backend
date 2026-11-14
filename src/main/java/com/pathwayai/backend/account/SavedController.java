package com.pathwayai.backend.account;

import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Saved programs and universities of the signed-in student (again: the account comes from the session, never from the URL). */
@RestController
@RequestMapping("/api/v1/account/saved")
class SavedController {

    private static final Pattern PROGRAM = Pattern.compile("^[a-z0-9]+(-{1,2}[a-z0-9]+)*$");
    private static final Pattern UNIVERSITY = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    private final SavedService saved;

    SavedController(SavedService saved) {
        this.saved = saved;
    }

    @GetMapping
    SavedService.SavedResponse list(Authentication authentication) {
        return saved.list(CurrentAccount.id(authentication));
    }

    @PutMapping("/programs/{identifier}")
    ResponseEntity<Void> saveProgram(@PathVariable String identifier, Authentication authentication) {
        saved.saveProgram(CurrentAccount.id(authentication), valid(identifier, PROGRAM, 250));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/programs/{identifier}")
    ResponseEntity<Void> removeProgram(@PathVariable String identifier, Authentication authentication) {
        saved.removeProgram(CurrentAccount.id(authentication), valid(identifier, PROGRAM, 250));
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/universities/{slug}")
    ResponseEntity<Void> saveUniversity(@PathVariable String slug, Authentication authentication) {
        saved.saveUniversity(CurrentAccount.id(authentication), valid(slug, UNIVERSITY, 120));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/universities/{slug}")
    ResponseEntity<Void> removeUniversity(@PathVariable String slug, Authentication authentication) {
        saved.removeUniversity(CurrentAccount.id(authentication), valid(slug, UNIVERSITY, 120));
        return ResponseEntity.noContent().build();
    }

    private static String valid(String value, Pattern pattern, int max) {
        if (value.length() > max || !pattern.matcher(value).matches()) {
            throw new AccountException(400, "VALIDATION", "That identifier is not valid.");
        }
        return value;
    }
}
