package com.emergencylink.controller;

import com.emergencylink.dto.HospitalDtos.ResourceBatchRequest;
import com.emergencylink.dto.HospitalDtos.ResourceDto;
import com.emergencylink.security.CurrentUser;
import com.emergencylink.service.ResourceService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/resources")
public class ResourceController {
    private final ResourceService resources;
    public ResourceController(ResourceService resources) { this.resources = resources; }

    @GetMapping("/mine")
    @PreAuthorize("hasAnyRole('REFERRING_HOSPITAL','RECEIVING_HOSPITAL')")
    public List<ResourceDto> mine() { return resources.mine(CurrentUser.get()); }

    @PutMapping("/mine")
    @PreAuthorize("hasAnyRole('REFERRING_HOSPITAL','RECEIVING_HOSPITAL')")
    public List<ResourceDto> update(@Valid @RequestBody ResourceBatchRequest req) {
        return resources.update(CurrentUser.get(), req);
    }
}
