package com.emergencylink.controller;

import com.emergencylink.dto.CommonDtos.PageResponse;
import com.emergencylink.dto.HospitalDtos.HospitalDto;
import com.emergencylink.dto.HospitalDtos.HospitalUpdateRequest;
import com.emergencylink.security.CurrentUser;
import com.emergencylink.service.HospitalService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/hospitals")
public class HospitalController {
    private final HospitalService hospitals;
    public HospitalController(HospitalService hospitals) { this.hospitals = hospitals; }

    @GetMapping
    public PageResponse<HospitalDto> search(@RequestParam(required = false) String q,
                                            @RequestParam(required = false) String city,
                                            @RequestParam(required = false) String specialty,
                                            @RequestParam(required = false) String facility,
                                            @RequestParam(defaultValue = "false") boolean availableOnly,
                                            @RequestParam(required = false) Boolean active,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "12") int size) {
        return hospitals.search(q, city, specialty, facility, availableOnly, active, CurrentUser.get(), page, size);
    }

    /** Full simulated network (all active hospitals with resources and activity) for the visualization. */
    @GetMapping("/network")
    public List<HospitalDto> network() { return hospitals.network(); }

    @GetMapping("/mine")
    public HospitalDto mine() { return hospitals.mine(CurrentUser.get()); }

    @GetMapping("/{id}")
    public HospitalDto get(@PathVariable Long id) { return hospitals.get(id, CurrentUser.get()); }

    @PutMapping("/{id}")
    public HospitalDto update(@PathVariable Long id, @Valid @RequestBody HospitalUpdateRequest req) {
        return hospitals.update(id, req, CurrentUser.get());
    }
}
