package com.emergencylink.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;

/** Append-only history of every change to a hospital's resource counts. */
@Entity
@Table(name = "resource_changes", indexes = @Index(name = "idx_resource_change_hospital", columnList = "hospital_id, created_at"))
@Getter @Setter @NoArgsConstructor
public class ResourceChange {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "hospital_id")
    private Hospital hospital;
    @Enumerated(EnumType.STRING) @Column(name = "resource_type", nullable = false, length = 30) private ResourceType resourceType;
    @Column(name = "old_total") private Integer oldTotal;
    @Column(name = "old_available") private Integer oldAvailable;
    @Column(name = "new_total", nullable = false) private int newTotal;
    @Column(name = "new_available", nullable = false) private int newAvailable;
    @Column(name = "changed_by", length = 200) private String changedBy;
    @Column(length = 500) private String reason;
    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
