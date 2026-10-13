package com.emergencylink.dto;

import com.emergencylink.dto.CommonDtos.ActivityDto;
import com.emergencylink.dto.HospitalDtos.ResourceDto;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class DashboardDtos {
    private DashboardDtos() {}

    public record DayCount(String date, long count) {}
    public record HospitalActivity(Long hospitalId, String hospitalName, long referrals) {}
    public record ResourceTotal(String type, String label, String unit, long total, long available, long hospitalsReporting, long staleHospitals) {}
    public record SpecialtyCount(String specialty, long count) {}

    public record DashboardDto(String role, Map<String, Long> kpis, Map<String, Long> statusDistribution,
                               Map<String, Long> urgencyDistribution, List<DayCount> referralsOverTime,
                               List<SpecialtyCount> topSpecialties, Double avgDecisionMinutes,
                               List<ActivityDto> recentActivity, List<ResourceDto> ownResources,
                               List<ResourceTotal> resourceOverview, List<HospitalActivity> hospitalActivity,
                               Instant generatedAt) {}

    public record AuditEventDto(Long id, String actorEmail, String actorRole, String action, String entityType,
                                Long entityId, String entityLabel, String detail, String reason, String outcome,
                                String before, String after, Instant createdAt) {}

    public record SystemStatusDto(String status, String database, boolean demoEnvironment, String version, String assistantMode,
                                  Instant serverTime, Instant lastResourceUpdate, long hospitals, long referrals,
                                  long staleResourceRecords, long staleThresholdMinutes) {}

    public record SearchResults(List<ReferralDtos.ReferralDto> referrals, List<HospitalDtos.HospitalDto> hospitals,
                                List<String> specialties, List<String> facilities) {}
}
