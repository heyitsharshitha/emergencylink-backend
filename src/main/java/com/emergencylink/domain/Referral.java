package com.emergencylink.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "referrals", indexes = {
        @Index(name = "idx_referral_status", columnList = "status"),
        @Index(name = "idx_referral_urgency", columnList = "urgency"),
        @Index(name = "idx_referral_referring", columnList = "referring_hospital_id"),
        @Index(name = "idx_referral_target", columnList = "target_hospital_id"),
        @Index(name = "idx_referral_created", columnList = "created_at")})
@Getter @Setter @NoArgsConstructor
public class Referral {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "referral_code", nullable = false, unique = true, length = 40)
    private String referralCode;

    /** Demo identifier only. Real patient identifiers must never be entered. */
    @Column(name = "patient_name", nullable = false, length = 120) private String patientName;
    private Integer age;
    @Column(length = 20) private String gender;
    @Column(name = "emergency_condition", nullable = false, length = 200) private String emergencyCondition;
    @Column(name = "clinical_summary", length = 2000) private String clinicalSummary;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Urgency urgency;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "required_specialty_id")
    private Specialty requiredSpecialty;
    @ManyToMany
    @JoinTable(name = "referral_facilities",
            joinColumns = @JoinColumn(name = "referral_id"),
            inverseJoinColumns = @JoinColumn(name = "facility_id"))
    private Set<Facility> requiredFacilities = new HashSet<>();
    @OneToMany(mappedBy = "referral", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ReferralRequirement> requirements = new ArrayList<>();

    @Column(name = "icu_required", nullable = false) private boolean icuRequired;
    @Column(name = "ventilator_required", nullable = false) private boolean ventilatorRequired;
    @Column(name = "oxygen_required", nullable = false) private boolean oxygenRequired;
    @Column(name = "blood_required", nullable = false) private boolean bloodRequired;
    @Column(name = "blood_group", length = 10) private String bloodGroup;
    @Column(name = "additional_requirements", length = 1000) private String additionalRequirements;

    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "referring_hospital_id")
    private Hospital referringHospital;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "target_hospital_id")
    private Hospital targetHospital;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "created_by")
    private User createdBy;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    private ReferralStatus status = ReferralStatus.CREATED;
    @Column(name = "admin_flagged", nullable = false) private boolean adminFlagged;
    @Column(name = "flag_reason", length = 500) private String flagReason;
    @Column(name = "flagged_at") private Instant flaggedAt;
    @Column(name = "decision_reason", length = 1000) private String decisionReason;
    @Column(name = "decided_at") private Instant decidedAt;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private Instant updatedAt;
}
