package com.emergencylink.repository;

import com.emergencylink.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ResourceChangeRepository extends JpaRepository<ResourceChange, Long> {
    List<ResourceChange> findTop100ByHospital_IdOrderByCreatedAtDescIdDesc(Long hospitalId);
}
