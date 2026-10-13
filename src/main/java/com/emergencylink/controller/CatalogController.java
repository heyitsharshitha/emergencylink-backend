package com.emergencylink.controller;

import com.emergencylink.dto.CommonDtos.CatalogDto;
import com.emergencylink.service.CatalogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public reference lists (specialties, facilities, resource types) used by forms, including registration. */
@RestController
@RequestMapping("/api/catalog")
public class CatalogController {
    private final CatalogService catalog;
    public CatalogController(CatalogService catalog) { this.catalog = catalog; }

    @GetMapping
    public CatalogDto catalog() { return catalog.catalog(); }
}
