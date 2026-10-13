package com.emergencylink.dto;

import com.emergencylink.domain.HospitalType;
import com.emergencylink.domain.Role;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class AuthDtos {
    private AuthDtos() {}

    public record LoginRequest(
            @NotBlank(message = "E-mail is required") @Email(message = "Enter a valid e-mail address") String email,
            @NotBlank(message = "Password is required") String password) {}

    public record RegisterRequest(
            @NotBlank(message = "Your name is required") @Size(max = 120) String fullName,
            @NotBlank(message = "E-mail is required") @Email(message = "Enter a valid e-mail address") @Size(max = 160) String email,
            @NotBlank(message = "Password is required") @Size(min = 8, max = 72, message = "Password must be 8 to 72 characters") String password,
            @NotNull(message = "Choose how your hospital will use EmergencyLink") Role role,
            @NotBlank(message = "Hospital name is required") @Size(max = 160) String hospitalName,
            @NotNull(message = "Hospital type is required") HospitalType hospitalType,
            @Size(max = 255) String address,
            @NotBlank(message = "City is required") @Size(max = 80) String city,
            @NotBlank(message = "State is required") @Size(max = 80) String state,
            @Size(max = 30) String phone,
            @Size(max = 60) String emergencyContact,
            @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
            @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,
            List<String> specialties,
            List<String> facilities) {}

    public record UserDto(Long id, String email, String fullName, Role role, Long hospitalId, String hospitalName, boolean active, Instant createdAt) {}

    public record AuthResponse(String token, String tokenType, Instant expiresAt, UserDto user) {}
}
