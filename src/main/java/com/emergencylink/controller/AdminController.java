package com.emergencylink.controller;

import com.emergencylink.domain.ReferralStatus;
import com.emergencylink.domain.Role;
import com.emergencylink.domain.Urgency;
import com.emergencylink.dto.AdminDtos.*;
import com.emergencylink.dto.AuthDtos.UserDto;
import com.emergencylink.dto.CommonDtos.PageResponse;
import com.emergencylink.dto.DashboardDtos.AuditEventDto;
import com.emergencylink.dto.HospitalDtos.HospitalDto;
import com.emergencylink.service.AdminHospitalService;
import com.emergencylink.service.AdminReportService;
import com.emergencylink.service.AdminResourceService;
import com.emergencylink.service.SettingsService;
import com.emergencylink.dto.ReferralDtos.ReferralDto;
import com.emergencylink.security.CurrentUser;
import com.emergencylink.service.AdminInsightService;
import com.emergencylink.service.AdminReferralService;
import com.emergencylink.service.AdminService;
import com.emergencylink.service.HospitalService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Every route under /api/admin is restricted to ROLE_ADMIN in SecurityConfig. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final AdminService admin;
    private final HospitalService hospitals;
    private final AdminReferralService referralOps;
    private final AdminInsightService insight;
    private final AdminHospitalService hospitalOps;
    private final AdminResourceService resourceOps;
    private final AdminReportService reports;
    private final SettingsService settings;

    public AdminController(AdminService admin, HospitalService hospitals, AdminReferralService referralOps, AdminInsightService insight,
                           AdminHospitalService hospitalOps, AdminResourceService resourceOps, AdminReportService reports, SettingsService settings) {
        this.hospitalOps = hospitalOps;
        this.resourceOps = resourceOps;
        this.reports = reports;
        this.settings = settings;
        this.admin = admin;
        this.hospitals = hospitals;
        this.referralOps = referralOps;
        this.insight = insight;
    }

    @GetMapping("/overview")
    public AdminOverviewDto overview() { return insight.overview(); }

    @PutMapping("/exceptions/state")
    public ExceptionSummaryDto exceptionState(@Valid @RequestBody ExceptionStateRequest body) {
        return insight.setState(body, CurrentUser.get());
    }

    @GetMapping("/settings")
    public SettingsDto settings() { return settings.current(); }

    @PutMapping("/settings")
    public SettingsDto updateSettings(@Valid @RequestBody SettingsUpdateRequest body) {
        return settings.update(body, CurrentUser.get());
    }

    @PostMapping("/hospitals")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public HospitalDto createHospital(@Valid @RequestBody HospitalCreateRequest body) {
        return hospitalOps.create(body, CurrentUser.get());
    }

    @PutMapping("/hospitals/{id}")
    public HospitalDto editHospital(@PathVariable Long id, @Valid @RequestBody HospitalEditRequest body) {
        return hospitalOps.edit(id, body, CurrentUser.get());
    }

    @GetMapping("/hospitals/{id}/resources")
    public List<com.emergencylink.dto.HospitalDtos.ResourceDto> hospitalResources(@PathVariable Long id) {
        return resourceOps.current(id);
    }

    @PutMapping("/hospitals/{id}/resources")
    public ResourceCorrectionResult correctResources(@PathVariable Long id, @Valid @RequestBody ResourceCorrectionRequest body) {
        return resourceOps.correct(id, body, CurrentUser.get());
    }

    @GetMapping("/hospitals/{id}/resource-history")
    public List<ResourceChangeDto> resourceHistory(@PathVariable Long id) { return resourceOps.history(id); }

    @GetMapping("/reports/summary")
    public ReportSummaryDto reportSummary(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.summary(from, to);
    }

    @GetMapping("/reports/export/{type}")
    public ResponseEntity<byte[]> exportReport(@PathVariable String type,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        String csv = reports.csv(type, from, to, CurrentUser.get());
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"emergencylink-" + type.replaceAll("[^a-z-]", "") + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/exceptions")
    public ExceptionSummaryDto exceptions() { return insight.exceptions(); }

    @PutMapping("/referrals/{id}")
    public ReferralDto editReferral(@PathVariable Long id, @Valid @RequestBody ReferralEditRequest body) {
        return referralOps.edit(id, body, CurrentUser.get());
    }

    @PostMapping("/referrals/{id}/reassign")
    public ReferralDto reassign(@PathVariable Long id, @Valid @RequestBody ReassignRequest body) {
        return referralOps.reassign(id, body, CurrentUser.get());
    }

    @PostMapping("/referrals/{id}/cancel")
    public ReferralDto cancel(@PathVariable Long id, @Valid @RequestBody ReasonRequest body) {
        return referralOps.cancel(id, body, CurrentUser.get());
    }

    @PostMapping("/referrals/{id}/flag")
    public ReferralDto flag(@PathVariable Long id, @Valid @RequestBody ReasonRequest body) {
        return referralOps.flag(id, body, CurrentUser.get());
    }

    @PostMapping("/referrals/{id}/resolve-flag")
    public ReferralDto resolveFlag(@PathVariable Long id, @Valid @RequestBody ReasonRequest body) {
        return referralOps.resolveFlag(id, body, CurrentUser.get());
    }

    @GetMapping("/referrals/{id}/notes")
    public List<AdminNoteDto> notes(@PathVariable Long id) { return referralOps.notes(id); }

    @PostMapping("/referrals/{id}/notes")
    public AdminNoteDto addNote(@PathVariable Long id, @Valid @RequestBody AdminNoteRequest body) {
        return referralOps.addNote(id, body, CurrentUser.get());
    }

    @GetMapping("/users")
    public PageResponse<UserDto> users(@RequestParam(required = false) String q, @RequestParam(required = false) Role role,
                                       @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return admin.users(q, role, page, size);
    }

    @PatchMapping("/users/{id}/active")
    public UserDto setUserActive(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return admin.setUserActive(id, Boolean.TRUE.equals(body.get("active")), reasonOf(body), CurrentUser.get());
    }

    @PatchMapping("/hospitals/{id}/active")
    public HospitalDto setHospitalActive(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return hospitals.setActive(id, Boolean.TRUE.equals(body.get("active")), reasonOf(body), CurrentUser.get());
    }

    @GetMapping("/audit")
    public PageResponse<AuditEventDto> audit(@RequestParam(required = false) String q, @RequestParam(required = false) String action,
                                             @RequestParam(required = false) String actor, @RequestParam(required = false) String entityType,
                                             @RequestParam(required = false) String outcome,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                             @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return admin.audit(q, action, actor, entityType, outcome, from, to, page, size);
    }

    @GetMapping("/reports/referrals.csv")
    public ResponseEntity<byte[]> referralsCsv(@RequestParam(required = false) ReferralStatus status,
                                               @RequestParam(required = false) Urgency urgency,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        String csv = admin.referralsCsv(status, urgency, from, to, CurrentUser.get());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"emergencylink-referrals.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    private static String reasonOf(Map<String, Object> body) {
        Object v = body.get("reason");
        return v instanceof String str && str.length() <= 500 ? str : null;
    }
}
