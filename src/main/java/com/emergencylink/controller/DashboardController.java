package com.emergencylink.controller;

import com.emergencylink.dto.DashboardDtos.DashboardDto;
import com.emergencylink.security.CurrentUser;
import com.emergencylink.service.DashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
    private final DashboardService dashboard;
    public DashboardController(DashboardService dashboard) { this.dashboard = dashboard; }

    @GetMapping
    public DashboardDto get() { return dashboard.dashboard(CurrentUser.get()); }
}
