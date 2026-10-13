package com.emergencylink.repository;

import com.emergencylink.domain.ReferralStatusHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;

public interface ReferralStatusHistoryRepository extends JpaRepository<ReferralStatusHistory, Long> {
    List<ReferralStatusHistory> findByReferral_IdOrderByCreatedAtAscIdAsc(Long referralId);

    @Query("select h from ReferralStatusHistory h join fetch h.referral r " +
            "where r.referringHospital.id = :hospitalId or r.targetHospital.id = :hospitalId order by h.createdAt desc")
    List<ReferralStatusHistory> recentForHospital(@Param("hospitalId") Long hospitalId, Pageable pageable);

    @Query("select h from ReferralStatusHistory h join fetch h.referral r order by h.createdAt desc")
    List<ReferralStatusHistory> recentAll(Pageable pageable);

    @Query("select h.eventType, count(h) from ReferralStatusHistory h where h.createdAt >= :from and h.createdAt < :to group by h.eventType")
    List<Object[]> countEventsBetween(@Param("from") Instant from, @Param("to") Instant to);
}
