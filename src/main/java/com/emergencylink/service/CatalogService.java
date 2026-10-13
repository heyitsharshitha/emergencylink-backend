package com.emergencylink.service;

import com.emergencylink.domain.Facility;
import com.emergencylink.domain.ResourceType;
import com.emergencylink.domain.Specialty;
import com.emergencylink.dto.CommonDtos.CatalogDto;
import com.emergencylink.dto.CommonDtos.ResourceTypeDto;
import com.emergencylink.exception.ApiException;
import com.emergencylink.repository.FacilityRepository;
import com.emergencylink.repository.SpecialtyRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class CatalogService {
    private final SpecialtyRepository specialties;
    private final FacilityRepository facilities;

    public CatalogService(SpecialtyRepository specialties, FacilityRepository facilities) {
        this.specialties = specialties;
        this.facilities = facilities;
    }

    @Cacheable("catalog")
    @Transactional(readOnly = true)
    public CatalogDto catalog() {
        return new CatalogDto(
                specialties.findAllByOrderByNameAsc().stream().map(Specialty::getName).toList(),
                facilities.findAllByOrderByNameAsc().stream().map(Facility::getName).toList(),
                Arrays.stream(ResourceType.values()).map(t -> new ResourceTypeDto(t.name(), t.label(), t.unit())).toList());
    }

    /** Finds or creates specialties by name (case-insensitive). Hospitals may declare new specialties. */
    @CacheEvict(value = "catalog", allEntries = true)
    @Transactional
    public Set<Specialty> resolveSpecialties(List<String> names) {
        Set<Specialty> out = new LinkedHashSet<>();
        if (names == null) return out;
        for (String n : names) {
            if (n == null || n.isBlank()) continue;
            String name = n.trim();
            out.add(specialties.findByNameIgnoreCase(name).orElseGet(() -> specialties.save(new Specialty(name))));
        }
        return out;
    }

    @CacheEvict(value = "catalog", allEntries = true)
    @Transactional
    public Set<Facility> resolveFacilities(List<String> names) {
        Set<Facility> out = new LinkedHashSet<>();
        if (names == null) return out;
        for (String n : names) {
            if (n == null || n.isBlank()) continue;
            String name = n.trim();
            out.add(facilities.findByNameIgnoreCase(name).orElseGet(() -> facilities.save(new Facility(name))));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Specialty requireSpecialty(String name) {
        return specialties.findByNameIgnoreCase(name.trim())
                .orElseThrow(() -> ApiException.badRequest("Unknown specialty: " + name));
    }

    @Transactional(readOnly = true)
    public Set<Facility> requireFacilities(List<String> names) {
        Set<Facility> out = new LinkedHashSet<>();
        if (names == null) return out;
        for (String n : names) {
            if (n == null || n.isBlank()) continue;
            out.add(facilities.findByNameIgnoreCase(n.trim())
                    .orElseThrow(() -> ApiException.badRequest("Unknown facility: " + n)));
        }
        return out;
    }
}
