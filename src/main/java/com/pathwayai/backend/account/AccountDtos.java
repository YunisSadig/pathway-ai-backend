package com.pathwayai.backend.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** Request and response shapes of the account endpoints. Entities and password hashes never leave the service. */
public final class AccountDtos {

    private AccountDtos() {
    }

    public record RegisterRequest(@NotBlank @Size(max = 254) String email, @NotNull @Size(max = 200) String password,
                                  @Size(max = 80) String displayName) {
    }

    public record LoginRequest(@NotBlank @Size(max = 254) String email, @NotNull @Size(max = 200) String password) {
    }

    public record EmailRequest(@NotBlank @Size(max = 254) String email) {
    }

    public record TokenRequest(@NotBlank @Size(max = 200) String token) {
    }

    public record ResetPasswordRequest(@NotBlank @Size(max = 200) String token, @NotNull @Size(max = 200) String newPassword) {
    }

    public record ChangePasswordRequest(@NotNull @Size(max = 200) String currentPassword, @NotNull @Size(max = 200) String newPassword) {
    }

    public record PasswordConfirmation(@NotNull @Size(max = 200) String password) {
    }

    public record AccountResponse(UUID id, String email, String displayName, String role, boolean emailVerified,
                                  boolean catalogueAccess, Instant createdAt, StudentProfileDtos.AvatarInfo avatar) {
    }

    /** What the website may offer, so it never shows a button that cannot work. */
    public record ConfigResponse(boolean registrationOpen, boolean emailDeliveryAvailable, boolean cataloguePublic) {
    }

    public record MessageResponse(String message) {
    }

    public record RegisterResponse(String status, String message, AccountResponse account) {
    }
}
