package com.emergencylink.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;

/** Ownership and review state for a derived exception. Only rows that differ from "open and unowned" are stored. */
@Entity
@Table(name = "exception_states")
@Getter @Setter @NoArgsConstructor
public class ExceptionState {
    @Id @Column(name = "exception_key", length = 80) private String key;
    @Column(nullable = false, length = 20) private String status;
    @Column(name = "owner_name", length = 160) private String ownerName;
    @Column(name = "owner_email", length = 160) private String ownerEmail;
    @Column(length = 500) private String note;
    @Column(name = "updated_at") private Instant updatedAt;
}
