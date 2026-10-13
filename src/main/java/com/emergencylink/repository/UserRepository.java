package com.emergencylink.repository;

import com.emergencylink.domain.Role;
import com.emergencylink.domain.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    boolean existsByEmailIgnoreCase(String email);

    /** Eagerly joins the hospital so callers outside a transaction never hit a LazyInitializationException. */
    @Query("select u from User u left join fetch u.hospital where lower(u.email) = lower(:email)")
    Optional<User> findByEmailWithHospital(@Param("email") String email);

    @Query("select u from User u left join fetch u.hospital where u.id = :id")
    Optional<User> findByIdWithHospital(@Param("id") Long id);

    List<User> findByHospital_IdAndActiveTrue(Long hospitalId);

    @Query(value = "select u from User u left join fetch u.hospital h where " +
            "(:q is null or lower(u.fullName) like lower(concat('%', :q, '%')) or lower(u.email) like lower(concat('%', :q, '%'))) " +
            "and (:role is null or u.role = :role)",
            countQuery = "select count(u) from User u where " +
            "(:q is null or lower(u.fullName) like lower(concat('%', :q, '%')) or lower(u.email) like lower(concat('%', :q, '%'))) " +
            "and (:role is null or u.role = :role)")
    Page<User> search(@Param("q") String q, @Param("role") Role role, Pageable pageable);

    long countByRole(Role role);

    @Query("select distinct u.hospital.id from User u where u.role = :role and u.active = true and u.hospital is not null")
    List<Long> findHospitalIdsByRole(@Param("role") Role role);

    default List<Long> findReceivingHospitalIds() { return findHospitalIdsByRole(Role.RECEIVING_HOSPITAL); }
}
