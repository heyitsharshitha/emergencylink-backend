package com.emergencylink.repository;

import com.emergencylink.domain.Referral;
import com.emergencylink.domain.ReferralStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;

public interface ReferralRepository extends JpaRepository<Referral, Long>, JpaSpecificationExecutor<Referral> {
    long countByStatus(ReferralStatus status);

    /** Referrals that are not completed or cancelled (declined referrals stay open because they can be re-routed). */
    @Query("select r from Referral r where r.status not in :done")
    List<Referral> findOpen(@Param("done") Collection<ReferralStatus> done);

    @Query("select r.status, count(r) from Referral r group by r.status")
    List<Object[]> countGroupedByStatus();

    @Query("select r.urgency, count(r) from Referral r group by r.urgency")
    List<Object[]> countGroupedByUrgency();

    /** Active (non-terminal, dispatched) referrals per target hospital, used by the network view. */
    @Query("select r.targetHospital.id, count(r) from Referral r where r.targetHospital is not null " +
            "and r.status not in :excluded group by r.targetHospital.id")
    List<Object[]> countActiveByTarget(@Param("excluded") Collection<ReferralStatus> excluded);
}
