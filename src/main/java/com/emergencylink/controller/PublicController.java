package com.emergencylink.controller;

import com.emergencylink.dto.DashboardDtos.SystemStatusDto;
import com.emergencylink.service.AdminService;
import com.emergencylink.service.HelpService;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** Unauthenticated endpoints: system/data-health status and the in-app help guide. */
@RestController
@RequestMapping("/api/public")
public class PublicController {
    private final AdminService admin;
    private final HelpService help;

    public PublicController(AdminService admin, HelpService help) {
        this.admin = admin;
        this.help = help;
    }

    @GetMapping("/status")
    public SystemStatusDto status() { return admin.status(); }

    @GetMapping("/help")
    public List<HelpService.HelpDoc> help(@RequestParam(required = false) String q) { return help.search(q, 20); }
}
