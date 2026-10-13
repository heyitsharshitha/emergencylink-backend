package com.emergencylink.repository;

import com.emergencylink.domain.AdminNote;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AdminNoteRepository extends JpaRepository<AdminNote, Long> {
    List<AdminNote> findByReferral_IdOrderByCreatedAtAscIdAsc(Long referralId);
}
