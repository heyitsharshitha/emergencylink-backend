package com.emergencylink.service;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Workflow documentation. The same documents power the in-app Help page and the assistant's retrieval step,
 * so both always give consistent guidance.
 */
@Service
public class HelpService {
    public record HelpDoc(String slug, String title, String category, String body) {}

    private static final List<HelpDoc> DOCS = List.of(
        new HelpDoc("overview", "What EmergencyLink does", "Getting started",
            "EmergencyLink is a demonstration platform for coordinating emergency patient referrals between hospitals. A referring hospital records what a patient transfer needs (specialty, ICU, facilities, blood, oxygen). The system compares those needs with each hospital's published availability and explains the result. People make every clinical and acceptance decision. It does not diagnose, prescribe or recommend treatment."),
        new HelpDoc("create-referral", "Creating a referral", "Referrals",
            "Referring hospitals open Referrals > New referral. The form has sections: patient demo identifier, emergency information, required specialty, required resources, facilities, optional preferred hospital, additional information and a review step. A completeness check lists missing coordination fields before you submit. Use only fictional demo identifiers, never real patient data."),
        new HelpDoc("matching", "Hospital matching and the score", "Referrals",
            "Matching is rule-based and transparent. For every hospital that can receive referrals the system checks specialty (30 points), facilities (20), ICU bed if requested (20), emergency or general bed (15), other resources such as ventilator, oxygen or blood (10) and emergency service (5). Checks that do not apply are left out. Each check is MATCHED, UNAVAILABLE or UNKNOWN. UNKNOWN means data is missing or stale and earns half the points, so the hospital must confirm. The score measures requirement coverage in published demo data. It is not a clinical risk score or a treatment recommendation. Distance is an estimate from simulated coordinates."),
        new HelpDoc("send", "Sending a referral", "Referrals",
            "After matching, the referring hospital chooses a receiving hospital and sends the referral. The status moves from MATCHING to SENT and the receiving hospital is notified. The referring hospital can cancel before a decision."),
        new HelpDoc("decisions", "Accept, decline or request information", "Receiving hospitals",
            "Opening a sent referral marks it VIEWED. The receiving hospital can then accept, decline with a required reason, or request more information with a message. If information is requested the referring hospital replies with the details and the referral returns to VIEWED. After acceptance either hospital can start the transfer and later mark it completed. If a referral is declined the referring hospital can run matching again to choose another hospital."),
        new HelpDoc("status-flow", "Referral status lifecycle", "Referrals",
            "CREATED, MATCHING, SENT, VIEWED, MORE_INFORMATION_REQUIRED, ACCEPTED or DECLINED, TRANSFER_IN_PROGRESS, COMPLETED. A referral can also be CANCELLED. The server enforces valid transitions and records each one in the timeline with who did it and when."),
        new HelpDoc("messages", "Messages on a referral", "Communication",
            "Messages are attached to a referral and visible only to the two hospitals involved and administrators. Messaging opens when the referral is sent. Each message shows the sender, hospital, role and time."),
        new HelpDoc("resources", "Updating resource availability", "Resources",
            "Hospitals update beds, ICU beds, ventilators, blood units, oxygen points, operating theatres and specialists on call under Resource Availability. Available cannot exceed total. Each update stores a timestamp and the person who made it. Information older than the freshness threshold (default 120 minutes) is flagged as stale and treated as unconfirmed in matching."),
        new HelpDoc("freshness", "Data freshness and stale information", "Resources",
            "A stale resource record means the hospital has not updated it recently. It still shows the last reported number and exact update time, but the platform labels it as needing confirmation rather than implying current availability. The threshold is configured with RESOURCE_STALE_MINUTES."),
        new HelpDoc("notifications", "Notifications", "Communication",
            "In-app notifications appear for new referrals, acceptance, decline, information requests, status changes and messages. Open the bell to review them and mark them read."),
        new HelpDoc("admin", "Administration", "Administration",
            "Administrators can see all hospitals, users, referrals, an audit log of important events, system analytics and a CSV export of referral analytics. Exports exclude patient identifiers. Administrators have read-only access to referral content."),
        new HelpDoc("limits", "Limitations of this prototype", "Getting started",
            "This is an academic prototype with simulated data. It is not connected to real hospital systems, does not hold real patient records, and must not be used for real emergencies. Availability, distances and compatibility are simulated. Clinicians and hospital staff make all decisions."),
        new HelpDoc("assistant", "Using the coordination assistant", "Assistant",
            "The assistant answers questions about referral workflow and the current demo data: which hospitals list a specialty, who has an ICU bed available, why a hospital scored as it did, and which resource data is stale. It never gives clinical advice. When no language-model key is configured it runs in clearly labelled demo mode using deterministic retrieval.")
    );

    @Cacheable("help")
    public List<HelpDoc> all() { return DOCS; }

    public List<HelpDoc> search(String q, int limit) {
        if (q == null || q.isBlank()) return DOCS.stream().limit(limit).toList();
        Set<String> terms = tokens(q);
        return DOCS.stream()
                .map(d -> Map.entry(d, score(d, terms)))
                .filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<HelpDoc, Integer>comparingByValue().reversed())
                .limit(limit).map(Map.Entry::getKey).toList();
    }

    private static int score(HelpDoc d, Set<String> terms) {
        String title = d.title().toLowerCase();
        String body = d.body().toLowerCase();
        int s = 0;
        for (String t : terms) {
            if (title.contains(t)) s += 4;
            if (body.contains(t)) s += 1;
        }
        return s;
    }

    static Set<String> tokens(String q) {
        return Arrays.stream(q.toLowerCase().split("[^a-z0-9]+")).filter(t -> t.length() > 2)
                .filter(t -> !STOP.contains(t)).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static final Set<String> STOP = Set.of("the", "and", "for", "with", "what", "how", "can", "does", "which", "who", "are", "from",
            "this", "that", "have", "has", "was", "why", "about", "please", "show", "list", "any");
}
