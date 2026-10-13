package com.emergencylink.controller;

import com.emergencylink.dto.CommonDtos.NotificationDto;
import com.emergencylink.dto.CommonDtos.PageResponse;
import com.emergencylink.security.CurrentUser;
import com.emergencylink.service.NotificationService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationService notifications;
    public NotificationController(NotificationService notifications) { this.notifications = notifications; }

    @GetMapping
    public PageResponse<NotificationDto> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return notifications.list(CurrentUser.get().id(), page, size);
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unread() { return notifications.unreadCount(CurrentUser.get().id()); }

    @PostMapping("/{id}/read")
    public NotificationDto read(@PathVariable Long id) { return notifications.markRead(id, CurrentUser.get().id()); }

    @PostMapping("/read-all")
    public Map<String, Integer> readAll() { return notifications.markAllRead(CurrentUser.get().id()); }
}
