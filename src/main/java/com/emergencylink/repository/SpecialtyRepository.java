package com.emergencylink.repository;

import com.emergencylink.domain.Specialty;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface SpecialtyRepository extends JpaRepository<Specialty, Long> {
    Optional<Specialty> findByNameIgnoreCase(String name);
    List<Specialty> findAllByOrderByNameAsc();
}
