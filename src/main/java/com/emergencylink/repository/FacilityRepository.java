package com.emergencylink.repository;

import com.emergencylink.domain.Facility;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface FacilityRepository extends JpaRepository<Facility, Long> {
    Optional<Facility> findByNameIgnoreCase(String name);
    List<Facility> findAllByOrderByNameAsc();
}
