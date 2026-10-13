package com.emergencylink.service;

import com.emergencylink.config.AppProperties;
import com.emergencylink.domain.*;
import com.emergencylink.dto.HospitalDtos.HospitalDto;
import com.emergencylink.dto.HospitalDtos.ResourceDto;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

@Component
public class HospitalMapper {
    private final AppProperties props;
    private SettingsService settings;
    public HospitalMapper(AppProperties props) { this.props = props; }

    /** Optional so the mapper can still be built without the settings service (for example in unit tests). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSettings(SettingsService settings) { this.settings = settings; }

    private long staleMinutes() { return settings != null ? settings.staleMinutes() : props.resources().staleAfterMinutes(); }

    public boolean isStale(Instant updatedAt) {
        return updatedAt == null || Duration.between(updatedAt, Instant.now()).toMinutes() > staleMinutes();
    }

    public ResourceDto toDto(HospitalResource r) {
        return new ResourceDto(r.getType(), r.getType().label(), r.getType().unit(), r.getTotal(), r.getAvailable(),
                r.getUpdatedAt(), r.getUpdatedBy(), isStale(r.getUpdatedAt()));
    }

    /** Must be called inside a transaction (touches lazy collections). */
    public HospitalDto toDto(Hospital h, List<HospitalResource> resources, long activeReferrals, boolean receivesReferrals) {
        List<ResourceDto> res = resources.stream()
                .sorted(Comparator.comparing(r -> r.getType().ordinal()))
                .map(this::toDto).toList();
        Instant last = resources.stream().map(HospitalResource::getUpdatedAt).max(Instant::compareTo).orElse(null);
        return new HospitalDto(h.getId(), h.getName(), h.getType(), h.getAddress(), h.getCity(), h.getState(), h.getPhone(),
                h.getEmail(), h.getEmergencyContact(), h.getLatitude(), h.getLongitude(), h.isEmergencyServices(), h.isActive(), receivesReferrals,
                h.getSpecialties().stream().map(Specialty::getName).sorted().toList(),
                h.getFacilities().stream().map(Facility::getName).sorted().toList(),
                res, last, isStale(last), activeReferrals, h.getCreatedAt());
    }
}
