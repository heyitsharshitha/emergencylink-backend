package com.emergencylink.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;

/** A named filter a user saved for the referral or hospital list. Private to its owner. */
@Entity
@Table(name = "saved_filters", indexes = @Index(name = "idx_saved_filter_user", columnList = "user_id, scope"))
@Getter @Setter @NoArgsConstructor
public class SavedFilter {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id")
    private User user;
    @Column(nullable = false, length = 20) private String scope;
    @Column(nullable = false, length = 80) private String name;
    @Column(name = "filter_json", nullable = false, length = 1000) private String filterJson;
    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
