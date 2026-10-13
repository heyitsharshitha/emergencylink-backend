package com.emergencylink.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "referral_requirements")
@Getter @Setter @NoArgsConstructor
public class ReferralRequirement {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "referral_id")
    private Referral referral;
    @Enumerated(EnumType.STRING) @Column(name = "resource_type", nullable = false, length = 30)
    private ResourceType resourceType;
    @Column(nullable = false) private int quantity = 1;
}
