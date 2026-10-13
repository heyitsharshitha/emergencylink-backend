package com.emergencylink.repository;

import com.emergencylink.domain.HospitalResource;
import com.emergencylink.domain.ResourceType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface HospitalResourceRepository extends JpaRepository<HospitalResource, Long> {
    List<HospitalResource> findByHospital_Id(Long hospitalId);
    Optional<HospitalResource> findByHospital_IdAndType(Long hospitalId, ResourceType type);

    @Query("select r from HospitalResource r join fetch r.hospital h where h.id in :ids")
    List<HospitalResource> findByHospitalIds(Collection<Long> ids);

    @Query("select r from HospitalResource r join fetch r.hospital h where h.active = true")
    List<HospitalResource> findAllActive();
}
