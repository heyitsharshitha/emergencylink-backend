package com.emergencylink.service;

import com.emergencylink.config.AppProperties;
import com.emergencylink.domain.*;
import com.emergencylink.dto.AssistantDtos.*;
import com.emergencylink.dto.HospitalDtos.HospitalDto;
import com.emergencylink.dto.HospitalDtos.ResourceDto;
import com.emergencylink.dto.ReferralDtos.MatchResultDto;
import com.emergencylink.dto.ReferralDtos.ReferralDto;
import com.emergencylink.exception.ApiException;
import com.emergencylink.security.AuthUser;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Retrieval-first coordination assistant.
 * 1) Retrieve: help documents (keyword relevance) + live application data (hospitals, availability, matching, referral).
 * 2) Answer: with an LLM when LLM_API_KEY is set (server-side only), otherwise a clearly labelled deterministic DEMO answer.
 * Context is capped and history truncated to keep token usage low. The assistant never gives clinical advice.
 */
@Service
public class AssistantService {
    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);
    private static final String DISCLAIMER = "Coordination information from simulated demo data only. Not clinical advice; hospital staff make every decision.";
    private static final int MAX_CONTEXT_CHARS = 5500;

    private final HelpService help;
    private final HospitalService hospitals;
    private final ReferralService referrals;
    private final CatalogService catalog;
    private final HospitalMapper mapper;
    private final AppProperties props;
    private final RestTemplate http;

    public AssistantService(HelpService help, HospitalService hospitals, ReferralService referrals, CatalogService catalog,
                            HospitalMapper mapper, AppProperties props, org.springframework.boot.web.client.RestTemplateBuilder builder) {
        this.help = help;
        this.hospitals = hospitals;
        this.referrals = referrals;
        this.catalog = catalog;
        this.mapper = mapper;
        this.props = props;
        this.http = builder.setConnectTimeout(Duration.ofSeconds(5)).setReadTimeout(Duration.ofSeconds(25)).build();
    }

    public boolean liveMode() { return props.llm().apiKey() != null && !props.llm().apiKey().isBlank(); }

    public List<SuggestedPrompt> suggestedPrompts(AuthUser u) {
        List<SuggestedPrompt> p = new ArrayList<>();
        switch (u.role()) {
            case REFERRING_HOSPITAL -> {
                p.add(new SuggestedPrompt("Explain the match results", "Which hospitals match this referral and why?", true));
                p.add(new SuggestedPrompt("ICU availability", "Which hospitals currently have an ICU bed available?", false));
                p.add(new SuggestedPrompt("Cardiology options", "Which hospitals list Cardiology?", false));
            }
            case RECEIVING_HOSPITAL -> {
                p.add(new SuggestedPrompt("Review requirements", "Summarise what this referral requires from us.", true));
                p.add(new SuggestedPrompt("Declining a referral", "How do I decline or request more information?", false));
            }
            case ADMIN -> p.add(new SuggestedPrompt("Stale data", "Which hospitals have stale resource information?", false));
        }
        p.add(new SuggestedPrompt("How scoring works", "How does the compatibility score work?", false));
        p.add(new SuggestedPrompt("Referral workflow", "Explain the referral status workflow.", false));
        return p;
    }

    @Transactional(readOnly = true)
    public ChatResponse chat(AuthUser u, ChatRequest req) {
        String msg = req.message().trim();
        String lower = msg.toLowerCase();
        List<String> sources = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        List<HospitalCard> cards = new ArrayList<>();
        StringBuilder ctx = new StringBuilder();

        // --- retrieval: documentation
        var docs = help.search(msg, 3);
        for (var d : docs) {
            ctx.append("DOC [").append(d.title()).append("]: ").append(d.body()).append("\n");
            sources.add("Help: " + d.title());
        }

        // --- retrieval: referral context + deterministic matching
        ReferralDto ref = null;
        List<MatchResultDto> matches = List.of();
        if (req.referralId() != null) {
            ref = referrals.get(req.referralId(), u); // enforces access control
            ctx.append("REFERRAL ").append(ref.referralCode()).append(": ").append(ref.emergencyCondition()).append("; urgency ")
               .append(ref.urgency()).append("; status ").append(ref.status()).append("; specialty ").append(ref.requiredSpecialty())
               .append("; ICU ").append(ref.icuRequired()).append("; ventilator ").append(ref.ventilatorRequired())
               .append("; oxygen ").append(ref.oxygenRequired()).append("; blood ").append(ref.bloodRequired())
               .append("; facilities ").append(ref.facilities()).append("; from ").append(ref.referringHospital().name())
               .append(ref.targetHospital() != null ? "; to " + ref.targetHospital().name() : "").append("\n");
            sources.add("Referral " + ref.referralCode());
            if (u.role() != Role.RECEIVING_HOSPITAL) {
                matches = referrals.previewMatches(req.referralId(), u);
                for (var m : matches.stream().limit(4).toList()) {
                    ctx.append("MATCH ").append(m.hospitalName()).append(" score ").append(m.score()).append("%: ")
                       .append(String.join(" | ", m.summary())).append("\n");
                }
                sources.add("Rule-based matching results");
            }
        }

        // --- retrieval: hospital data relevant to the question
        List<HospitalDto> network = hospitals.network();
        var cat = catalog.catalog();
        Optional<String> specialty = cat.specialties().stream().filter(s -> lower.contains(s.toLowerCase())).findFirst();
        Optional<String> facility = cat.facilities().stream().filter(s -> lower.contains(s.toLowerCase())).findFirst();
        boolean icuQ = lower.contains("icu") || lower.contains("intensive");
        boolean bedQ = lower.contains("bed") || lower.contains("capacity") || lower.contains("available");
        boolean staleQ = lower.contains("stale") || lower.contains("fresh") || lower.contains("outdated") || lower.contains("up to date");
        boolean bloodQ = lower.contains("blood");
        boolean ventQ = lower.contains("ventilator");

        List<HospitalDto> relevant = network.stream().filter(HospitalDto::receivesReferrals)
                .filter(h -> specialty.map(s -> h.specialties().stream().anyMatch(x -> x.equalsIgnoreCase(s))).orElse(true))
                .filter(h -> facility.map(f -> h.facilities().stream().anyMatch(x -> x.equalsIgnoreCase(f))).orElse(true))
                .collect(Collectors.toList());
        if (icuQ) relevant = relevant.stream().filter(h -> avail(h, ResourceType.ICU_BED) > 0).collect(Collectors.toList());
        if (bloodQ) relevant = relevant.stream().filter(h -> avail(h, ResourceType.BLOOD_UNITS) > 0).collect(Collectors.toList());
        if (ventQ) relevant = relevant.stream().filter(h -> avail(h, ResourceType.VENTILATOR) > 0).collect(Collectors.toList());
        if (staleQ) relevant = network.stream().filter(HospitalDto::resourcesStale).collect(Collectors.toList());

        boolean dataIntent = specialty.isPresent() || facility.isPresent() || icuQ || bedQ || staleQ || bloodQ || ventQ;
        if (dataIntent) {
            for (HospitalDto h : relevant.stream().limit(6).toList()) {
                List<String> hl = new ArrayList<>();
                for (ResourceType t : List.of(ResourceType.ICU_BED, ResourceType.EMERGENCY_BED, ResourceType.GENERAL_BED, ResourceType.VENTILATOR, ResourceType.BLOOD_UNITS)) {
                    h.resources().stream().filter(r -> r.type() == t).findFirst()
                            .ifPresent(r -> hl.add(r.label() + ": " + r.available() + "/" + r.total() + (r.stale() ? " (stale)" : "")));
                }
                cards.add(new HospitalCard(h.id(), h.name(), h.city(), null, hl, h.resourcesStale(), h.lastResourceUpdate()));
                ctx.append("HOSPITAL ").append(h.name()).append(" (").append(h.city()).append("): specialties ").append(h.specialties())
                   .append("; ").append(String.join(", ", hl)).append("; updated ").append(h.lastResourceUpdate()).append("\n");
            }
            sources.add("Hospital network data");
        }
        if (matches.size() > 0) {
            for (var m : matches.stream().limit(4).toList()) {
                cards.add(new HospitalCard(m.hospitalId(), m.hospitalName(), m.city(), m.score(), m.summary(), m.resourcesStale(), m.resourcesUpdatedAt()));
            }
        }
        long staleCount = network.stream().filter(HospitalDto::resourcesStale).count();
        if (staleCount > 0) notes.add(staleCount + " hospital(s) have stale or missing resource data and need confirmation.");
        if (dataIntent && relevant.isEmpty()) notes.add("No hospital in the demo data matches that combination of requirements.");
        if (ctx.length() > MAX_CONTEXT_CHARS) ctx.setLength(MAX_CONTEXT_CHARS);

        String answer = null;
        String mode = "DEMO";
        if (liveMode()) {
            try {
                answer = callLlm(msg, ctx.toString(), req.history());
                mode = "LIVE";
            } catch (Exception ex) {
                log.warn("LLM call failed, falling back to demo answer: {}", ex.getMessage());
                notes.add("The language model was unreachable, so a deterministic demo answer is shown.");
            }
        }
        if (answer == null) answer = demoAnswer(msg, ref, matches, relevant, dataIntent, docs, specialty, facility, icuQ, staleQ);
        return new ChatResponse(answer, mode, sources.stream().distinct().toList(), cards, notes, Instant.now());
    }

    private static int avail(HospitalDto h, ResourceType t) {
        return h.resources().stream().filter(r -> r.type() == t).mapToInt(ResourceDto::available).findFirst().orElse(0);
    }

    private String demoAnswer(String msg, ReferralDto ref, List<MatchResultDto> matches, List<HospitalDto> relevant, boolean dataIntent,
                              List<HelpService.HelpDoc> docs, Optional<String> specialty, Optional<String> facility, boolean icuQ, boolean staleQ) {
        StringBuilder sb = new StringBuilder();
        String l = msg.toLowerCase();
        boolean matchIntent = ref != null && !matches.isEmpty() && (l.contains("match") || l.contains("hospital") || l.contains("why") || l.contains("suitable") || l.contains("best"));
        if (matchIntent) {
            sb.append("### Rule-based matches for ").append(ref.referralCode()).append("\n");
            sb.append("Ranked by the transparent compatibility score (requirement coverage, not a clinical judgement).\n\n");
            int rank = 1;
            for (var m : matches.stream().limit(4).toList()) {
                sb.append("**").append(rank++).append(". ").append(m.hospitalName()).append("** — ").append(m.score()).append("% compatibility");
                if (m.distanceKm() != null) sb.append(", about ").append(m.distanceKm()).append(" km (estimated)");
                sb.append("\n");
                for (String s : m.summary()) sb.append("- ").append(s).append("\n");
                if (m.resourcesStale()) sb.append("- ⚠ Resource data is stale; confirm availability by phone before relying on it.\n");
                sb.append("\n");
            }
        } else if (ref != null && (l.contains("summar") || l.contains("require"))) {
            sb.append("### ").append(ref.referralCode()).append(" requirements\n");
            sb.append("- Urgency: **").append(ref.urgency()).append("**, status **").append(ref.status()).append("**\n");
            sb.append("- Specialty: ").append(ref.requiredSpecialty()).append("\n");
            sb.append("- ICU: ").append(ref.icuRequired() ? "required" : "not requested").append("; ventilator: ").append(ref.ventilatorRequired() ? "required" : "no")
              .append("; oxygen: ").append(ref.oxygenRequired() ? "required" : "no").append("; blood: ").append(ref.bloodRequired() ? "required" : "no").append("\n");
            if (!ref.facilities().isEmpty()) sb.append("- Facilities: ").append(String.join(", ", ref.facilities())).append("\n");
            if (ref.additionalRequirements() != null) sb.append("- Additional: ").append(ref.additionalRequirements()).append("\n");
        } else if (dataIntent) {
            String what = (staleQ ? "hospitals with stale resource data" : "hospitals")
                    + specialty.map(s -> " listing " + s).orElse("") + facility.map(f -> " with " + f).orElse("") + (icuQ ? " and an ICU bed reported available" : "");
            if (relevant.isEmpty()) {
                sb.append("I found no ").append(what).append(" in the current demo data.\n");
            } else {
                sb.append("### ").append(relevant.size()).append(" result(s): ").append(what).append("\n");
                for (HospitalDto h : relevant.stream().limit(6).toList()) {
                    sb.append("- **").append(h.name()).append("** (").append(h.city()).append(")");
                    h.resources().stream().filter(r -> r.type() == ResourceType.ICU_BED).findFirst()
                            .ifPresent(r -> sb.append(" — ICU ").append(r.available()).append("/").append(r.total()));
                    sb.append(h.resourcesStale() ? " ⚠ stale" : " · fresh").append(" · updated ").append(h.lastResourceUpdate()).append("\n");
                }
            }
        } else if (!docs.isEmpty()) {
            sb.append("### ").append(docs.get(0).title()).append("\n").append(docs.get(0).body()).append("\n");
            if (docs.size() > 1) sb.append("\nRelated: ").append(docs.stream().skip(1).map(HelpService.HelpDoc::title).collect(Collectors.joining(", "))).append(".\n");
        } else {
            sb.append("I can help with the referral workflow, how compatibility scoring works, and what the demo data says about hospital specialties and availability. Try one of the suggested prompts.\n");
        }
        sb.append("\n> ").append(DISCLAIMER);
        return sb.toString();
    }

    private String callLlm(String message, String context, List<ChatTurn> history) {
        String system = "You are the EmergencyLink coordination assistant for an academic prototype using simulated demo data. "
                + "Answer ONLY from the CONTEXT below. If the context lacks the answer or data is stale, say so. "
                + "Never give diagnoses, treatment advice or clinical recommendations; hospital staff decide. "
                + "Be concise, use Markdown, and mention data freshness when relevant.\n\nCONTEXT:\n" + context;
        List<Map<String, String>> messages = new ArrayList<>();
        if (history != null) {
            int from = Math.max(0, history.size() - props.llm().historyMessages());
            for (ChatTurn t : history.subList(from, history.size())) {
                if (t.content() == null || t.content().isBlank()) continue;
                String role = "assistant".equals(t.role()) ? "assistant" : "user";
                messages.add(Map.of("role", role, "content", t.content().length() > 800 ? t.content().substring(0, 800) : t.content()));
            }
        }
        messages.add(Map.of("role", "user", "content", message));
        // Anthropic requires the first message to be from the user.
        while (!messages.isEmpty() && !"user".equals(messages.get(0).get("role"))) messages.remove(0);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.llm().model());
        body.put("max_tokens", props.llm().maxTokens());
        body.put("system", system);
        body.put("messages", messages);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("x-api-key", props.llm().apiKey());
        h.set("anthropic-version", "2023-06-01");
        JsonNode res = http.postForObject(props.llm().baseUrl() + "/v1/messages", new HttpEntity<>(body, h), JsonNode.class);
        if (res == null || !res.has("content") || res.get("content").isEmpty()) throw new ApiException(HttpStatus.BAD_GATEWAY, "Empty model response");
        StringBuilder out = new StringBuilder();
        for (JsonNode part : res.get("content")) if ("text".equals(part.path("type").asText())) out.append(part.path("text").asText());
        return out.toString().trim() + "\n\n> " + DISCLAIMER;
    }
}
