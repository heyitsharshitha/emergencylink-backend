package com.emergencylink.repository;

import com.emergencylink.domain.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    @Query(value = "select n from Notification n left join fetch n.referral where n.recipient.id = :uid order by n.createdAt desc",
            countQuery = "select count(n) from Notification n where n.recipient.id = :uid")
    Page<Notification> findForRecipient(@Param("uid") Long recipientId, Pageable pageable);

    long countByRecipient_IdAndReadFalse(Long recipientId);

    Optional<Notification> findByIdAndRecipient_Id(Long id, Long recipientId);

    @Modifying
    @Query("update Notification n set n.read = true where n.recipient.id = :uid and n.read = false")
    int markAllRead(@Param("uid") Long recipientId);
}
