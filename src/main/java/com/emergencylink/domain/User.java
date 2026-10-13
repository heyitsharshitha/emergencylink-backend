package com.emergencylink.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;

@Entity
@Table(name = "users", indexes = @Index(name = "idx_users_hospital", columnList = "hospital_id"))
@Getter @Setter @NoArgsConstructor
public class User {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 160)
    private String email;
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;
    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    private Role role;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "hospital_id")
    private Hospital hospital;
    @Column(nullable = false)
    private boolean active = true;
    @CreationTimestamp @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
