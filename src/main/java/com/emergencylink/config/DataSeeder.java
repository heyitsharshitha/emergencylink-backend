package com.emergencylink.config;

import com.emergencylink.domain.*;
import com.emergencylink.dto.HospitalDtos.ResourceBatchRequest;
import com.emergencylink.dto.HospitalDtos.ResourceUpdateRequest;
import com.emergencylink.dto.ReferralDtos.*;
import com.emergencylink.repository.*;
import com.emergencylink.security.AuthUser;
import com.emergencylink.service.ReferralService;
import com.emergencylink.service.ResourceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Seeds clearly FICTIONAL demo hospitals, users, availability and referrals on first launch.
 * Referrals are created by driving the real services, so history, messages and notifications are genuine records.
 * Demo password for every account: Demo@1234
 */
@Component
public class DataSeeder implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);
    public static final String DEMO_PASSWORD = "Demo@1234";

    private final AppProperties props;
    private final UserRepository users;
    private final HospitalRepository hospitals;
    private final SpecialtyRepository specialties;
    private final FacilityRepository facilities;
    private final HospitalResourceRepository resources;
    private final ReferralRepository referralRepo;
    private final PasswordEncoder encoder;
    private final ReferralService referrals;
    private final ResourceService resourceService;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public DataSeeder(AppProperties props, UserRepository users, HospitalRepository hospitals, SpecialtyRepository specialties,
                      FacilityRepository facilities, HospitalResourceRepository resources, ReferralRepository referralRepo,
                      PasswordEncoder encoder, ReferralService referrals, ResourceService resourceService, JdbcTemplate jdbc, TransactionTemplate tx) {
        this.props = props; this.users = users; this.hospitals = hospitals; this.specialties = specialties; this.facilities = facilities;
        this.resources = resources; this.referralRepo = referralRepo; this.encoder = encoder; this.referrals = referrals;
        this.resourceService = resourceService; this.jdbc = jdbc; this.tx = tx;
    }

    private record H(String name, HospitalType type, String city, String address, double lat, double lng, String phone, boolean receives,
                     String email, String person, List<String> specs, List<String> facs, int[] res, boolean stale) {}

    @Override
    public void run(String... args) {
        if (!props.seed().enabled() || users.count() > 0) return;
        log.info("Seeding fictional demo data...");
        tx.executeWithoutResult(s -> seedCore());
        seedReferrals();
        log.info("Demo data ready. Demo password for all accounts: {}", DEMO_PASSWORD);
    }

    // res order: GENERAL total/avail, EMERGENCY total/avail, ICU total/avail, VENT total/avail, BLOOD total/avail, OXYGEN total/avail, OT total/avail, SPECIALIST total/avail
    private void seedCore() {
        for (String s : List.of("Cardiology", "Neurology", "Neurosurgery", "Trauma & Orthopaedics", "Paediatrics", "Obstetrics & Gynaecology",
                "Nephrology", "Pulmonology", "Burns & Plastic Surgery", "General Surgery", "Emergency Medicine", "Critical Care", "Gastroenterology", "Oncology"))
            specialties.save(new Specialty(s));
        for (String f : List.of("CT Scan", "MRI", "Cath Lab", "Dialysis Unit", "Blood Bank", "NICU", "PICU", "Burns Unit", "Trauma Centre",
                "Stroke Unit", "Operating Theatre Complex", "Helipad", "24x7 Pharmacy"))
            facilities.save(new Facility(f));

        List<H> list = List.of(
            new H("Deccan Care Medical Centre", HospitalType.MULTI_SPECIALTY, "Hyderabad", "Demo Road 12, Hitech Zone", 17.4435, 78.3772, "+91 40 5550 0101", true,
                "deccan.care@demo.emergencylink", "Dr. Asha Rao (Demo)",
                List.of("Cardiology", "Emergency Medicine", "Critical Care", "General Surgery", "Paediatrics", "Pulmonology"),
                List.of("CT Scan", "MRI", "Blood Bank", "PICU", "Operating Theatre Complex", "24x7 Pharmacy"),
                new int[]{120, 34, 20, 6, 24, 5, 14, 4, 60, 38, 80, 52, 6, 3, 10, 6}, false),
            new H("Hitech Heart Institute", HospitalType.SPECIALTY_CENTRE, "Hyderabad", "Demo Avenue 4, Madhapur", 17.4486, 78.3908, "+91 40 5550 0102", true,
                "hitech.heart@demo.emergencylink", "Dr. Vikram Nair (Demo)",
                List.of("Cardiology", "Critical Care", "Emergency Medicine", "Nephrology"),
                List.of("Cath Lab", "CT Scan", "Blood Bank", "Operating Theatre Complex", "Dialysis Unit"),
                new int[]{70, 18, 12, 4, 30, 2, 18, 5, 40, 22, 50, 31, 5, 2, 6, 3}, false),
            new H("Banjara Trauma & Neuro Centre", HospitalType.TERTIARY_CARE, "Hyderabad", "Demo Lane 8, Banjara Hills", 17.4156, 78.4347, "+91 40 5550 0103", true,
                "banjara.trauma@demo.emergencylink", "Dr. Meera Iyer (Demo)",
                List.of("Neurology", "Neurosurgery", "Trauma & Orthopaedics", "Emergency Medicine", "Critical Care", "General Surgery"),
                List.of("CT Scan", "MRI", "Trauma Centre", "Stroke Unit", "Blood Bank", "Helipad", "Operating Theatre Complex"),
                new int[]{150, 41, 25, 8, 28, 0, 16, 2, 70, 45, 120, 70, 8, 4, 12, 7}, false),
            new H("Musi Riverside Hospital", HospitalType.MULTI_SPECIALTY, "Hyderabad", "Demo Marg 21, Old City", 17.3616, 78.4747, "+91 40 5550 0104", true,
                "musi.riverside@demo.emergencylink", "Dr. Farhan Ali (Demo)",
                List.of("Burns & Plastic Surgery", "General Surgery", "Obstetrics & Gynaecology", "Paediatrics", "Emergency Medicine"),
                List.of("Burns Unit", "CT Scan", "Blood Bank", "NICU", "Operating Theatre Complex"),
                new int[]{90, 25, 14, 5, 12, 3, 8, 3, 35, 14, 60, 40, 5, 2, 7, 4}, true),
            new H("Nizam Children's & Women's Hospital", HospitalType.SPECIALTY_CENTRE, "Hyderabad", "Demo Street 3, Nampally", 17.3920, 78.4660, "+91 40 5550 0105", true,
                "nizam.cw@demo.emergencylink", "Dr. Kavita Menon (Demo)",
                List.of("Paediatrics", "Obstetrics & Gynaecology", "Critical Care", "Emergency Medicine"),
                List.of("NICU", "PICU", "Blood Bank", "CT Scan"),
                new int[]{80, 19, 10, 3, 16, 4, 10, 3, 30, 17, 50, 33, 4, 2, 6, 3}, false),
            new H("Secunderabad District Hospital", HospitalType.DISTRICT, "Secunderabad", "Demo Nagar 9, Secunderabad", 17.4399, 78.4983, "+91 40 5550 0201", false,
                "secbad.district@demo.emergencylink", "Dr. Ramesh Babu (Demo)",
                List.of("General Surgery", "Emergency Medicine", "Obstetrics & Gynaecology"),
                List.of("Blood Bank", "24x7 Pharmacy"),
                new int[]{200, 60, 15, 6, 6, 1, 4, 1, 25, 9, 90, 55, 3, 1, 5, 3}, false),
            new H("Medchal Community Hospital", HospitalType.COMMUNITY, "Medchal", "Demo Colony 2, Medchal", 17.6297, 78.4816, "+91 8418 555 301", false,
                "medchal.community@demo.emergencylink", "Dr. Lakshmi Prasad (Demo)",
                List.of("Emergency Medicine", "General Surgery"),
                List.of("24x7 Pharmacy"),
                new int[]{60, 22, 6, 3, 2, 0, 1, 0, 8, 3, 30, 19, 1, 1, 2, 1}, false),
            new H("Sangareddy Area Hospital", HospitalType.DISTRICT, "Sangareddy", "Demo Chowk 5, Sangareddy", 17.6247, 78.0867, "+91 8455 555 302", false,
                "sangareddy.area@demo.emergencylink", "Dr. Naveen Kumar (Demo)",
                List.of("Emergency Medicine", "Paediatrics"),
                List.of("Blood Bank"),
                new int[]{70, 28, 8, 2, 3, 1, 2, 1, 10, 5, 36, 20, 2, 1, 3, 2}, false));

        String[] referringEmail = {"secunderabad.referrer", "medchal.referrer", "sangareddy.referrer"};
        int refIdx = 0;
        String hash = encoder.encode(DEMO_PASSWORD);
        User admin = new User();
        admin.setEmail("admin@emergencylink.demo"); admin.setFullName("Demo Administrator"); admin.setPasswordHash(hash); admin.setRole(Role.ADMIN);
        users.save(admin);

        Instant now = Instant.now();
        for (H d : list) {
            Hospital draft = new Hospital();
            draft.setName(d.name()); draft.setType(d.type()); draft.setCity(d.city()); draft.setState("Telangana"); draft.setAddress(d.address());
            draft.setLatitude(d.lat()); draft.setLongitude(d.lng()); draft.setPhone(d.phone()); draft.setEmail(d.email()); draft.setEmergencyContact(d.phone());
            d.specs().forEach(n -> draft.getSpecialties().add(specialties.findByNameIgnoreCase(n).orElseThrow()));
            d.facs().forEach(n -> draft.getFacilities().add(facilities.findByNameIgnoreCase(n).orElseThrow()));
            Hospital h = hospitals.save(draft);

            ResourceType[] types = {ResourceType.GENERAL_BED, ResourceType.EMERGENCY_BED, ResourceType.ICU_BED, ResourceType.VENTILATOR,
                    ResourceType.BLOOD_UNITS, ResourceType.OXYGEN_POINTS, ResourceType.OPERATING_THEATRE, ResourceType.SPECIALIST_ON_CALL};
            for (int i = 0; i < types.length; i++) {
                HospitalResource r = new HospitalResource();
                r.setHospital(h); r.setType(types[i]); r.setTotal(d.res()[i * 2]); r.setAvailable(d.res()[i * 2 + 1]);
                r.setUpdatedAt(d.stale() ? now.minus(7, ChronoUnit.HOURS) : now.minus(10 + i * 7L, ChronoUnit.MINUTES));
                r.setUpdatedBy("Demo seed");
                resources.save(r);
            }
            User u = new User();
            u.setFullName(d.person()); u.setPasswordHash(hash); u.setHospital(h);
            if (d.receives()) {
                u.setRole(Role.RECEIVING_HOSPITAL);
                u.setEmail(d.name().toLowerCase().split("[ ,&']")[0] + ".receiver@emergencylink.demo");
            } else {
                u.setRole(Role.REFERRING_HOSPITAL);
                u.setEmail(referringEmail[refIdx++] + "@emergencylink.demo");
            }
            users.save(u);
        }
    }

    // ------------------------------------------------------------------ demo referrals

    private AuthUser principal(String email) {
        User u = users.findByEmailWithHospital(email).orElseThrow();
        return new AuthUser(u.getId(), u.getEmail(), u.getFullName(), u.getRole(), u.getHospital().getId(), u.getHospital().getName());
    }

    private Long hid(String name) { return hospitals.findByActiveTrue().stream().filter(h -> h.getName().equals(name)).findFirst().orElseThrow().getId(); }

    private ReferralCreateRequest req(String pt, int age, String gender, String cond, String summary, Urgency urg, String spec, List<String> facs,
                                      boolean icu, boolean vent, boolean o2, boolean blood, String group, Long target) {
        return new ReferralCreateRequest(pt, age, gender, cond, summary, urg, spec, facs, List.of(), icu, vent, o2, blood, group, null, target);
    }

    private void seedReferrals() {
        AuthUser sec = principal("secunderabad.referrer@emergencylink.demo");
        AuthUser med = principal("medchal.referrer@emergencylink.demo");
        AuthUser san = principal("sangareddy.referrer@emergencylink.demo");
        AuthUser deccan = principal("deccan.receiver@emergencylink.demo");
        AuthUser heart = principal("hitech.receiver@emergencylink.demo");
        AuthUser banjara = principal("banjara.receiver@emergencylink.demo");
        AuthUser musi = principal("musi.receiver@emergencylink.demo");
        AuthUser nizam = principal("nizam.receiver@emergencylink.demo");
        Long hHeart = hid("Hitech Heart Institute"), hBanj = hid("Banjara Trauma & Neuro Centre"), hDec = hid("Deccan Care Medical Centre"),
                hMusi = hid("Musi Riverside Hospital"), hNizam = hid("Nizam Children's & Women's Hospital");
        List<Long> ids = new ArrayList<>();

        // 1 completed cardiac transfer
        var r1 = referrals.create(req("DEMO-PT-1001", 58, "Male", "Acute chest pain, cardiac catheterisation needed", "Demo scenario: needs cath lab and ICU monitoring.",
                Urgency.CRITICAL, "Cardiology", List.of("Cath Lab"), true, false, true, false, null, null), med);
        referrals.runMatching(r1.id(), med); referrals.send(r1.id(), new SendRequest(hHeart, "Cath lab and ICU bed needed"), med);
        referrals.markViewed(r1.id(), heart); referrals.requestInformation(r1.id(), new RequiredNoteRequest("Please share latest ECG reading and current oxygen saturation."), heart);
        referrals.provideInformation(r1.id(), new RequiredNoteRequest("Demo values: ECG attached to file DEMO-ECG-01, SpO2 94% on oxygen."), med);
        referrals.accept(r1.id(), new NoteRequest("ICU bed 3 reserved. Cath lab team informed."), heart);
        referrals.startTransfer(r1.id(), med); referrals.complete(r1.id(), heart); ids.add(r1.id());

        // 2 pending urgent trauma (sent, not yet viewed)
        var r2 = referrals.create(req("DEMO-PT-1002", 34, "Male", "Road traffic injury with suspected head trauma", "Demo scenario.",
                Urgency.URGENT, "Neurosurgery", List.of("CT Scan", "Trauma Centre"), true, true, true, true, "O+", null), sec);
        referrals.runMatching(r2.id(), sec); referrals.send(r2.id(), new SendRequest(hBanj, null), sec); ids.add(r2.id());

        // 3 awaiting information
        var r3 = referrals.create(req("DEMO-PT-1003", 67, "Female", "Suspected stroke needing specialist review", "Demo scenario.",
                Urgency.CRITICAL, "Neurology", List.of("Stroke Unit", "CT Scan"), true, false, true, false, null, null), san);
        referrals.runMatching(r3.id(), san); referrals.send(r3.id(), new SendRequest(hBanj, null), san);
        referrals.markViewed(r3.id(), banjara); referrals.requestInformation(r3.id(), new RequiredNoteRequest("Time of symptom onset and current medication list, please."), banjara); ids.add(r3.id());

        // 4 declined
        var r4 = referrals.create(req("DEMO-PT-1004", 6, "Female", "Paediatric respiratory distress", "Demo scenario.",
                Urgency.URGENT, "Paediatrics", List.of("PICU"), true, false, true, false, null, null), med);
        referrals.runMatching(r4.id(), med); referrals.send(r4.id(), new SendRequest(hDec, null), med);
        referrals.markViewed(r4.id(), deccan); referrals.decline(r4.id(), new RequiredNoteRequest("PICU capacity fully committed. Please route to another paediatric centre."), deccan); ids.add(r4.id());

        // 5 created only
        var r5 = referrals.create(req("DEMO-PT-1005", 45, "Male", "Complex fracture needing orthopaedic surgery", "Demo scenario.",
                Urgency.STANDARD, "Trauma & Orthopaedics", List.of("Operating Theatre Complex"), false, false, false, false, null, null), sec); ids.add(r5.id());

        // 6 matching in progress
        var r6 = referrals.create(req("DEMO-PT-1006", 72, "Male", "Acute neurological deficit", "Demo scenario.",
                Urgency.CRITICAL, "Neurology", List.of("Stroke Unit"), true, false, true, false, null, null), san);
        referrals.runMatching(r6.id(), san); ids.add(r6.id());

        // 7 accepted burns
        var r7 = referrals.create(req("DEMO-PT-1007", 29, "Female", "Major burns requiring specialist unit", "Demo scenario.",
                Urgency.URGENT, "Burns & Plastic Surgery", List.of("Burns Unit"), false, false, true, true, "B+", null), sec);
        referrals.runMatching(r7.id(), sec); referrals.send(r7.id(), new SendRequest(hMusi, null), sec);
        referrals.markViewed(r7.id(), musi); referrals.accept(r7.id(), new NoteRequest("Burns unit bed ready."), musi); ids.add(r7.id());

        // 8 older completed paediatric
        var r8 = referrals.create(req("DEMO-PT-1008", 3, "Male", "Neonatal intensive care required", "Demo scenario.",
                Urgency.CRITICAL, "Paediatrics", List.of("NICU"), true, true, true, false, null, null), med);
        referrals.runMatching(r8.id(), med); referrals.send(r8.id(), new SendRequest(hNizam, null), med);
        referrals.markViewed(r8.id(), nizam); referrals.accept(r8.id(), new NoteRequest("NICU incubator available."), nizam);
        referrals.startTransfer(r8.id(), nizam); referrals.complete(r8.id(), med); ids.add(r8.id());

        // 9 transfer in progress
        var r9 = referrals.create(req("DEMO-PT-1009", 61, "Male", "Respiratory failure needing ventilator support", "Demo scenario.",
                Urgency.CRITICAL, "Critical Care", List.of("CT Scan"), true, true, true, false, null, null), san);
        referrals.runMatching(r9.id(), san); referrals.send(r9.id(), new SendRequest(hDec, null), san);
        referrals.markViewed(r9.id(), deccan); referrals.accept(r9.id(), null, deccan); referrals.startTransfer(r9.id(), san); ids.add(r9.id());

        referrals.postMessage(r2.id(), new MessageRequest("Ambulance is 15 minutes away. Please confirm the receiving bay."), sec);
        spreadTimestamps(ids);
    }

    /** Spreads the seeded records over the past two weeks so trends and timelines look realistic. */
    private void spreadTimestamps(List<Long> referralIds) {
        Instant now = Instant.now();
        int[] hoursAgo = {300, 5, 9, 52, 40, 3, 30, 200, 8};
        for (int i = 0; i < referralIds.size(); i++) {
            Long id = referralIds.get(i);
            Instant start = now.minus(hoursAgo[i], ChronoUnit.HOURS);
            List<Long> hist = jdbc.queryForList("select id from referral_status_history where referral_id = ? order by id", Long.class, id);
            Instant t = start;
            for (Long hId : hist) {
                jdbc.update("update referral_status_history set created_at = ? where id = ?", Timestamp.from(t), hId);
                t = t.plus(7, ChronoUnit.MINUTES);
            }
            List<Long> msgs = jdbc.queryForList("select id from referral_messages where referral_id = ? order by id", Long.class, id);
            Instant m = start.plus(20, ChronoUnit.MINUTES);
            for (Long mId : msgs) {
                jdbc.update("update referral_messages set created_at = ? where id = ?", Timestamp.from(m), mId);
                m = m.plus(9, ChronoUnit.MINUTES);
            }
            Instant end = t.minus(7, ChronoUnit.MINUTES);
            jdbc.update("update referrals set created_at = ?, updated_at = ?, decided_at = case when decided_at is null then null else ? end where id = ?",
                    Timestamp.from(start), Timestamp.from(end.isAfter(now) ? now : end), Timestamp.from(start.plus(35, ChronoUnit.MINUTES)), id);
        }
        jdbc.update("update notifications set created_at = ?", Timestamp.from(now.minus(20, ChronoUnit.MINUTES)));
    }
}
