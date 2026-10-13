package com.emergencylink.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;

/** Runtime-editable operational threshold (for example how long a referral may wait). Not a clinical rule. */
@Entity
@Table(name = "system_settings")
@Getter @Setter @NoArgsConstructor
public class SystemSetting {
    @Id @Column(name = "setting_key", length = 60) private String key;
    @Column(name = "setting_value", nullable = false, length = 200) private String value;
    @Column(name = "updated_by", length = 160) private String updatedBy;
    @Column(name = "updated_at") private Instant updatedAt;
}
