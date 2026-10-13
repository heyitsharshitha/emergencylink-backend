package com.emergencylink.repository;

import com.emergencylink.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface SavedFilterRepository extends JpaRepository<SavedFilter, Long> {
    List<SavedFilter> findByUser_IdAndScopeOrderByNameAsc(Long userId, String scope);
    Optional<SavedFilter> findByIdAndUser_Id(Long id, Long userId);
    Optional<SavedFilter> findByUser_IdAndScopeAndNameIgnoreCase(Long userId, String scope, String name);
    long countByUser_Id(Long userId);
}
