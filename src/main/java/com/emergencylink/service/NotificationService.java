package com.emergencylink.service;

import com.emergencylink.domain.Hospital;
import com.emergencylink.domain.Notification;
import com.emergencylink.domain.Referral;
import com.emergencylink.domain.User;
import com.emergencylink.dto.CommonDtos.NotificationDto;
import com.emergencylink.dto.CommonDtos.PageResponse;
import com.emergencylink.exception.ApiException;
import com.emergencylink.repository.NotificationRepository;
import com.emergencylink.repository.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;

@Service
public class NotificationService {
    private final NotificationRepository notifications;
    private final UserRepository users;

    public NotificationService(NotificationRepository notifications, UserRepository users) {
        this.notifications = notifications;
        this.users = users;
    }

    /** Creates an in-app notification for every active user of a hospital, except the actor. */
    @Transactional
    public void notifyHospital(Hospital hospital, Referral referral, String type, String title, String message, Long excludeUserId) {
        if (hospital == null) return;
        for (User u : users.findByHospital_IdAndActiveTrue(hospital.getId())) {
            if (u.getId().equals(excludeUserId)) continue;
            Notification n = new Notification();
            n.setRecipient(u);
            n.setReferral(referral);
            n.setType(type);
            n.setTitle(title);
            n.setMessage(message != null && message.length() > 500 ? message.substring(0, 500) : message);
            notifications.save(n);
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationDto> list(Long userId, int page, int size) {
        return PageResponse.of(notifications.findForRecipient(userId, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)))
                .map(this::toDto));
    }

    @Transactional(readOnly = true)
    public Map<String, Long> unreadCount(Long userId) {
        return Map.of("unread", notifications.countByRecipient_IdAndReadFalse(userId));
    }

    @Transactional
    public NotificationDto markRead(Long id, Long userId) {
        Notification n = notifications.findByIdAndRecipient_Id(id, userId).orElseThrow(() -> ApiException.notFound("Notification"));
        n.setRead(true);
        return toDto(n);
    }

    @Transactional
    public Map<String, Integer> markAllRead(Long userId) {
        return Map.of("updated", notifications.markAllRead(userId));
    }

    private NotificationDto toDto(Notification n) {
        Referral r = n.getReferral();
        return new NotificationDto(n.getId(), n.getType(), n.getTitle(), n.getMessage(), n.isRead(),
                r == null ? null : r.getId(), r == null ? null : r.getReferralCode(), n.getCreatedAt());
    }
}
