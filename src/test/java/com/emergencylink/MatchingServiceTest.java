package com.emergencylink;

import com.emergencylink.config.AppProperties;
import com.emergencylink.domain.*;
import com.emergencylink.dto.ReferralDtos.MatchResultDto;
import com.emergencylink.repository.HospitalRepository;
import com.emergencylink.repository.HospitalResourceRepository;
import com.emergencylink.repository.UserRepository;
import com.emergencylink.service.HospitalMapper;
import com.emergencylink.service.MatchingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

class MatchingServiceTest {
    HospitalRepository hospitals = Mockito.mock(HospitalRepository.class);
    HospitalResourceRepository resourceRepo = Mockito.mock(HospitalResourceRepository.class);
    UserRepository users = Mockito.mock(UserRepository.class);
    MatchingService service;
    Hospital origin, good, noIcu, stale;
    Referral referral;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties(new AppProperties.Jwt("x".repeat(40), 60), new AppProperties.Cors(List.of()),
                new AppProperties.Resources(120), new AppProperties.Seed(false), new AppProperties.Llm("", "", "", 100, 6));
        service = new MatchingService(hospitals, resourceRepo, users, new HospitalMapper(props));

        Specialty cardio = spec(1L, "Cardiology");
        Facility cath = fac(1L, "Cath Lab");
        origin = hospital(1L, "Origin");
        good = hospital(2L, "Good Heart"); good.getSpecialties().add(cardio); good.getFacilities().add(cath);
        noIcu = hospital(3L, "No ICU Heart"); noIcu.getSpecialties().add(cardio); noIcu.getFacilities().add(cath);
        stale = hospital(4L, "Stale Heart"); stale.getSpecialties().add(cardio); stale.getFacilities().add(cath);

        referral = new Referral();
        referral.setReferringHospital(origin);
        referral.setRequiredSpecialty(cardio);
        referral.getRequiredFacilities().add(cath);
        referral.setIcuRequired(true);

        when(hospitals.findByActiveTrue()).thenReturn(List.of(origin, good, noIcu, stale));
        when(users.findReceivingHospitalIds()).thenReturn(List.of(2L, 3L, 4L));
        Instant fresh = Instant.now().minus(5, ChronoUnit.MINUTES);
        Instant old = Instant.now().minus(9, ChronoUnit.HOURS);
        when(resourceRepo.findByHospitalIds(anyCollection())).thenReturn(List.of(
                res(good, ResourceType.ICU_BED, 5, fresh), res(good, ResourceType.EMERGENCY_BED, 5, fresh),
                res(noIcu, ResourceType.ICU_BED, 0, fresh), res(noIcu, ResourceType.EMERGENCY_BED, 5, fresh),
                res(stale, ResourceType.ICU_BED, 5, old), res(stale, ResourceType.EMERGENCY_BED, 5, old)));
    }

    @Test void fullyCompatibleHospitalScoresHundredAndRanksFirst() {
        List<MatchResultDto> r = service.match(referral);
        assertEquals("Good Heart", r.get(0).hospitalName());
        assertEquals(100, r.get(0).score());
    }

    @Test void originHospitalIsNeverACandidate() {
        assertTrue(service.match(referral).stream().noneMatch(m -> m.hospitalId().equals(1L)));
    }

    @Test void unavailableIcuLowersScoreAndIsExplained() {
        MatchResultDto m = service.match(referral).stream().filter(x -> x.hospitalId() == 3L).findFirst().orElseThrow();
        assertTrue(m.score() < 100);
        assertEquals(MatchingService.UNAVAILABLE, m.checks().stream().filter(c -> c.key().equals("icu")).findFirst().orElseThrow().status());
    }

    @Test void staleDataIsFlaggedUnknownNotAvailable() {
        MatchResultDto m = service.match(referral).stream().filter(x -> x.hospitalId() == 4L).findFirst().orElseThrow();
        assertTrue(m.resourcesStale());
        assertEquals(MatchingService.UNKNOWN, m.checks().stream().filter(c -> c.key().equals("icu")).findFirst().orElseThrow().status());
        assertTrue(m.score() < 100);
    }

    @Test void hospitalsWithoutReceivingAccountsAreExcluded() {
        when(users.findReceivingHospitalIds()).thenReturn(List.of(2L));
        assertEquals(1, service.match(referral).size());
    }

    private static Specialty spec(Long id, String n) { Specialty s = new Specialty(n); s.setId(id); return s; }
    private static Facility fac(Long id, String n) { Facility f = new Facility(n); f.setId(id); return f; }
    private static Hospital hospital(Long id, String n) { Hospital h = new Hospital(); h.setId(id); h.setName(n); h.setCity("Demo"); h.setState("Demo"); return h; }
    private static HospitalResource res(Hospital h, ResourceType t, int avail, Instant at) {
        HospitalResource r = new HospitalResource(); r.setHospital(h); r.setType(t); r.setTotal(10); r.setAvailable(avail); r.setUpdatedAt(at); return r;
    }
}
