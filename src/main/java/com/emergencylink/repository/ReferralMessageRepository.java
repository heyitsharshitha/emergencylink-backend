package com.emergencylink.repository;

import com.emergencylink.domain.ReferralMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ReferralMessageRepository extends JpaRepository<ReferralMessage, Long> {
    List<ReferralMessage> findByReferral_IdOrderByCreatedAtAscIdAsc(Long referralId);
}
