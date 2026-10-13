package com.emergencylink.dto;

import java.time.Instant;
import java.util.List;

public final class CommonDtos {
    private CommonDtos() {}

    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
        public static <T> PageResponse<T> of(org.springframework.data.domain.Page<T> p) {
            return new PageResponse<>(p.getContent(), p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
        }
    }

    public record NotificationDto(Long id, String type, String title, String message, boolean read,
                                  Long referralId, String referralCode, Instant createdAt) {}

    public record ActivityDto(Long referralId, String referralCode, String eventType, String description,
                              String actorLabel, Instant createdAt) {}

    public record CatalogDto(List<String> specialties, List<String> facilities, List<ResourceTypeDto> resourceTypes) {}
    public record ResourceTypeDto(String type, String label, String unit) {}
}
