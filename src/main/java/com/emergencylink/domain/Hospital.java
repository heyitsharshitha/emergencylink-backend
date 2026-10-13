package com.emergencylink.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "hospitals", indexes = {
        @Index(name = "idx_hospital_city", columnList = "city"),
        @Index(name = "idx_hospital_active", columnList = "active")})
@Getter @Setter @NoArgsConstructor
public class Hospital {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 160)
    private String name;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    private HospitalType type = HospitalType.MULTI_SPECIALTY;
    @Column(length = 255) private String address;
    @Column(nullable = false, length = 80) private String city;
    @Column(nullable = false, length = 80) private String state;
    @Column(length = 30) private String phone;
    @Column(length = 160) private String email;
    @Column(name = "emergency_contact", length = 60) private String emergencyContact;
    private Double latitude;
    private Double longitude;
    @Column(name = "emergency_services", nullable = false) private boolean emergencyServices = true;
    @Column(nullable = false) private boolean active = true;

    @ManyToMany
    @JoinTable(name = "hospital_specialties",
            joinColumns = @JoinColumn(name = "hospital_id"),
            inverseJoinColumns = @JoinColumn(name = "specialty_id"))
    private Set<Specialty> specialties = new HashSet<>();

    @ManyToMany
    @JoinTable(name = "hospital_facilities",
            joinColumns = @JoinColumn(name = "hospital_id"),
            inverseJoinColumns = @JoinColumn(name = "facility_id"))
    private Set<Facility> facilities = new HashSet<>();

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private Instant updatedAt;
}
