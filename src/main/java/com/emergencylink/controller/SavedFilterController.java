package com.emergencylink.controller;

import com.emergencylink.dto.AdminDtos.SavedFilterDto;
import com.emergencylink.dto.AdminDtos.SavedFilterRequest;
import com.emergencylink.security.CurrentUser;
import com.emergencylink.service.SavedFilterService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** Saved list filters. Every call is scoped to the signed-in user; ids belonging to someone else return 404. */
@RestController
@RequestMapping("/api/saved-filters")
public class SavedFilterController {
    private final SavedFilterService service;

    public SavedFilterController(SavedFilterService service) { this.service = service; }

    @GetMapping
    public List<SavedFilterDto> list(@RequestParam String scope) { return service.list(scope, CurrentUser.get()); }

    @PostMapping
    public SavedFilterDto save(@Valid @RequestBody SavedFilterRequest body) { return service.save(body, CurrentUser.get()); }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id, CurrentUser.get());
        return ResponseEntity.noContent().build();
    }
}
