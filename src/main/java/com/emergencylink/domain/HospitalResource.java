package com.emergencylink.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "hospital_resources",
        uniqueConstraints = @UniqueConstraint(name = "uk_hospital_resource", columnNames = {"hospital_id", "type"}))
@Getter @Setter @NoArgsConstructor
public class HospitalResource {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "hospital_id")
    private Hospital hospital;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    private ResourceType type;
    @Column(nullable = false) private int total;
    @Column(nullable = false) private int available;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
    @Column(name = "updated_by", length = 120) private String updatedBy;
}
