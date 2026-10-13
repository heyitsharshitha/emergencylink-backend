package com.emergencylink.repository;

import com.emergencylink.domain.Hospital;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface HospitalRepository extends JpaRepository<Hospital, Long>, JpaSpecificationExecutor<Hospital> {
    boolean existsByNameIgnoreCase(String name);

    @EntityGraph(attributePaths = {"specialties", "facilities"})
    List<Hospital> findByActiveTrue();

    @EntityGraph(attributePaths = {"specialties", "facilities"})
    @Query("select h from Hospital h where h.id = :id")
    Optional<Hospital> findDetailById(@Param("id") Long id);

    long countByActiveTrue();
}
