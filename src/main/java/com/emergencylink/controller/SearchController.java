package com.emergencylink.controller;

import com.emergencylink.dto.DashboardDtos.SearchResults;
import com.emergencylink.security.CurrentUser;
import com.emergencylink.service.CatalogService;
import com.emergencylink.service.HospitalService;
import com.emergencylink.service.ReferralService;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** Global quick search. Reuses the scoped list services, so users never see records they cannot access. */
@RestController
@RequestMapping("/api/search")
public class SearchController {
    private final ReferralService referrals;
    private final HospitalService hospitals;
    private final CatalogService catalog;

    public SearchController(ReferralService referrals, HospitalService hospitals, CatalogService catalog) {
        this.referrals = referrals;
        this.hospitals = hospitals;
        this.catalog = catalog;
    }

    @GetMapping
    public SearchResults search(@RequestParam String q) {
        var user = CurrentUser.get();
        String term = q == null ? "" : q.trim();
        if (term.length() < 2) return new SearchResults(List.of(), List.of(), List.of(), List.of());
        String lower = term.toLowerCase();
        var cat = catalog.catalog();
        return new SearchResults(
                referrals.list(user, term, null, null, null, null, null, null, null, "updatedAt", "desc", 0, 5).content(),
                hospitals.search(term, null, null, null, false, null, user, 0, 5).content(),
                cat.specialties().stream().filter(s -> s.toLowerCase().contains(lower)).limit(5).toList(),
                cat.facilities().stream().filter(s -> s.toLowerCase().contains(lower)).limit(5).toList());
    }
}
