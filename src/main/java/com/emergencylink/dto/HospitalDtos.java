package com.emergencylink.dto;

import com.emergencylink.domain.HospitalType;
import com.emergencylink.domain.ResourceType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class HospitalDtos {
    private HospitalDtos() {}

    public record ResourceDto(ResourceType type, String label, String unit, int total, int available,
                              Instant updatedAt, String updatedBy, boolean stale) {}

    public record HospitalDto(Long id, String name, HospitalType type, String address, String city, String state,
                              String phone, String email, String emergencyContact, Double latitude, Double longitude,
                              boolean emergencyServices, boolean active, boolean receivesReferrals, List<String> specialties, List<String> facilities,
                              List<ResourceDto> resources, Instant lastResourceUpdate, boolean resourcesStale,
                              long activeReferrals, Instant createdAt) {}

    public record HospitalUpdateRequest(
            @NotBlank(message = "Hospital name is required") @Size(max = 160) String name,
            @NotNull HospitalType type,
            @Size(max = 255) String address,
            @NotBlank(message = "City is required") @Size(max = 80) String city,
            @NotBlank(message = "State is required") @Size(max = 80) String state,
            @Size(max = 30) String phone,
            @Email @Size(max = 160) String email,
            @Size(max = 60) String emergencyContact,
            @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
            @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,
            Boolean emergencyServices,
            List<String> specialties,
            List<String> facilities) {}

    public record ResourceUpdateRequest(
            @NotNull ResourceType type,
            @Min(value = 0, message = "Total cannot be negative") int total,
            @Min(value = 0, message = "Available cannot be negative") int available) {}

    public record ResourceBatchRequest(@NotEmpty(message = "Provide at least one resource") @Valid List<ResourceUpdateRequest> updates) {}
}
