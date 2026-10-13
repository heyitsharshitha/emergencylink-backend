package com.emergencylink.dto;

import com.emergencylink.domain.ReferralStatus;
import com.emergencylink.domain.ResourceType;
import com.emergencylink.domain.Urgency;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class ReferralDtos {
    private ReferralDtos() {}

    public record ResourceRequirementDto(@NotNull ResourceType type, @Min(1) @Max(500) int quantity) {}

    public record ReferralCreateRequest(
            @NotBlank(message = "Patient demo identifier is required") @Size(max = 120) String patientName,
            @Min(value = 0, message = "Age cannot be negative") @Max(value = 120, message = "Enter a realistic age") Integer age,
            @Size(max = 20) String gender,
            @NotBlank(message = "Emergency condition is required") @Size(max = 200) String emergencyCondition,
            @Size(max = 2000, message = "Summary must be 2000 characters or fewer") String clinicalSummary,
            @NotNull(message = "Urgency level is required") Urgency urgency,
            @NotBlank(message = "Required specialty is required") String requiredSpecialty,
            List<String> facilities,
            @Valid List<ResourceRequirementDto> resources,
            boolean icuRequired, boolean ventilatorRequired, boolean oxygenRequired, boolean bloodRequired,
            @Size(max = 10) String bloodGroup,
            @Size(max = 1000) String additionalRequirements,
            Long targetHospitalId) {}

    public record HospitalRef(Long id, String name, String city, String phone, String emergencyContact) {}

    public record ReferralDto(Long id, String referralCode, String patientName, Integer age, String gender,
                              String emergencyCondition, String clinicalSummary, Urgency urgency, String requiredSpecialty,
                              List<String> facilities, List<ResourceRequirementDto> resources,
                              boolean icuRequired, boolean ventilatorRequired, boolean oxygenRequired, boolean bloodRequired,
                              String bloodGroup, String additionalRequirements,
                              HospitalRef referringHospital, HospitalRef targetHospital,
                              ReferralStatus status, List<String> allowedActions,
                              String decisionReason, Instant decidedAt, String createdByName,
                              Instant createdAt, Instant updatedAt, boolean adminFlagged, String flagReason) {}

    public record NoteRequest(@Size(max = 1000) String note) {}
    public record RequiredNoteRequest(@NotBlank(message = "A reason or message is required") @Size(max = 1000) String note) {}
    public record SendRequest(@NotNull(message = "Choose a receiving hospital") Long hospitalId, @Size(max = 1000) String note) {}

    public record MessageRequest(@NotBlank(message = "Message cannot be empty") @Size(max = 2000) String body) {}
    public record MessageDto(Long id, Long referralId, String body, Long senderId, String senderName, String senderRole,
                             Long senderHospitalId, String senderHospital, Instant createdAt) {}

    public record TimelineEventDto(Long id, String eventType, ReferralStatus fromStatus, ReferralStatus toStatus,
                                   String note, String actorLabel, Instant createdAt) {}

    public record MatchCheckDto(String key, String label, String status, int weight, int earned, String detail) {}
    public record MatchResultDto(Long hospitalId, String hospitalName, String city, int score, Double distanceKm,
                                 String distanceNote, boolean emergencyService, Instant resourcesUpdatedAt,
                                 boolean resourcesStale, List<MatchCheckDto> checks, List<String> summary) {}
}
