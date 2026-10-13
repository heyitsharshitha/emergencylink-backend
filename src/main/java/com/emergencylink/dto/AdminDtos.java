package com.emergencylink.dto;

import com.emergencylink.domain.Urgency;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class AdminDtos {
    private AdminDtos() {}

    /** Fields left null are not changed. A reason is mandatory for every privileged edit. */
    public record ReferralEditRequest(Urgency urgency, @Size(max = 80) String requiredSpecialty,
                                      @Size(max = 1000) String additionalRequirements,
                                      @NotBlank(message = "A reason is required") @Size(max = 500) String reason) {}

    public record ReassignRequest(@NotNull(message = "Choose a hospital") Long hospitalId,
                                  @NotBlank(message = "A reason is required") @Size(max = 500) String reason) {}

    public record ReasonRequest(@NotBlank(message = "A reason is required") @Size(max = 500) String reason) {}

    public record AdminNoteRequest(@NotBlank(message = "A note cannot be empty") @Size(max = 2000) String body) {}

    public record AdminNoteDto(Long id, String body, String authorLabel, Instant createdAt) {}

    /** One derived data-quality or workflow exception, merged with its stored review state (OPEN, IN_PROGRESS or REVIEWED). */
    public record ExceptionDto(String id, String type, String severity, String title, String explanation,
                               String entityType, Long entityId, String entityLabel, Instant detectedAt, String route,
                               String status, String ownerName, String note) {}

    public record ExceptionSummaryDto(int total, int high, int medium, int low, int reviewed, long waitThresholdMinutes,
                                      long staleAfterMinutes, List<ExceptionDto> items) {}

    public record ExceptionStateRequest(@NotBlank String id, @NotBlank String status, @Size(max = 500) String note) {}

    public record SettingsDto(long waitMinutes, long staleMinutes, long defaultWaitMinutes, long defaultStaleMinutes,
                              String updatedBy, Instant updatedAt) {}

    public record SettingsUpdateRequest(@Min(value = 5, message = "Waiting threshold must be at least 5 minutes") @Max(value = 1440, message = "Waiting threshold cannot exceed 1440 minutes") long waitMinutes,
                                        @Min(value = 15, message = "Freshness threshold must be at least 15 minutes") @Max(value = 10080, message = "Freshness threshold cannot exceed 10080 minutes") long staleMinutes,
                                        @NotBlank(message = "A reason is required") @Size(max = 500) String reason) {}

    public record HospitalCreateRequest(@NotNull @Valid HospitalDtos.HospitalUpdateRequest hospital, @Size(max = 500) String reason) {}

    public record HospitalEditRequest(@NotNull @Valid HospitalDtos.HospitalUpdateRequest hospital,
                                      @NotBlank(message = "A reason is required") @Size(max = 500) String reason) {}

    public record ResourceCorrectionRequest(@NotEmpty(message = "Provide at least one resource") @Valid List<HospitalDtos.ResourceUpdateRequest> updates,
                                            @NotBlank(message = "A reason is required") @Size(max = 500) String reason) {}

    public record ResourceCorrectionResult(List<HospitalDtos.ResourceDto> resources, List<String> warnings) {}

    public record ResourceChangeDto(Long id, String type, String label, Integer oldTotal, Integer oldAvailable, int newTotal,
                                    int newAvailable, String changedBy, String reason, Instant createdAt) {}

    public record SavedFilterRequest(@NotBlank @Size(max = 80) String name, @NotBlank @Size(max = 20) String scope,
                                     @NotBlank @Size(max = 1000) String filter) {}

    public record SavedFilterDto(Long id, String name, String scope, String filter, Instant createdAt) {}

    public record DayCountDto(String date, long count) {}
    public record HospitalActivityDto(Long hospitalId, String hospitalName, boolean active, long sent, long received,
                                      long accepted, long declined, long completed) {}
    public record StaleResourceDto(Long hospitalId, String hospitalName, String type, int available, int total,
                                   Instant updatedAt, Long ageMinutes) {}
    public record CompletenessDto(Long hospitalId, String hospitalName, int percent, List<String> missing) {}
    public record ResponseTimeDto(int sampleSize, Double avgMinutes, Double medianMinutes, Double minMinutes, Double maxMinutes) {}

    public record ReportSummaryDto(String from, String to, Instant generatedAt, long totalReferrals,
                                   Map<String, Long> byStatus, Map<String, Long> byUrgency, List<DayCountDto> volumeByDay,
                                   long acceptedDecisions, long declinedDecisions, Double acceptanceRate,
                                   long completedEvents, long cancelledEvents, ResponseTimeDto responseTime,
                                   List<HospitalActivityDto> hospitalActivity, List<StaleResourceDto> staleResources,
                                   List<CompletenessDto> profileCompleteness, Map<String, Long> auditByAction,
                                   long staleAfterMinutes) {}

    public record AdminOverviewDto(long hospitalsTotal, long hospitalsActive, long usersTotal, long usersActive,
                                   long activeReferrals, long awaitingAction, long criticalOrUrgentActive,
                                   long completed, long declinedOrCancelled, long flaggedReferrals, long overdueReferrals,
                                   long staleResourceRecords, int openExceptions,
                                   Map<String, Long> byStatus, Map<String, Long> byUrgency,
                                   Double avgCreationToDecisionMinutes, int decisionSampleSize, long waitThresholdMinutes,
                                   Instant generatedAt) {}
}
