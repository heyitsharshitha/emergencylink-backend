package com.emergencylink.service;

import com.emergencylink.domain.SavedFilter;
import com.emergencylink.dto.AdminDtos.SavedFilterDto;
import com.emergencylink.dto.AdminDtos.SavedFilterRequest;
import com.emergencylink.exception.ApiException;
import com.emergencylink.repository.SavedFilterRepository;
import com.emergencylink.repository.UserRepository;
import com.emergencylink.security.AuthUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Set;

/** Named list filters that belong to one user. Other users can never read, change or delete them. */
@Service
public class SavedFilterService {
    private static final Set<String> SCOPES = Set.of("referrals", "hospitals");
    private static final int MAX_PER_USER = 20;

    private final SavedFilterRepository filters;
    private final UserRepository users;
    private final ObjectMapper mapper;

    public SavedFilterService(SavedFilterRepository filters, UserRepository users, ObjectMapper mapper) {
        this.filters = filters;
        this.users = users;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<SavedFilterDto> list(String scope, AuthUser user) {
        requireScope(scope);
        return filters.findByUser_IdAndScopeOrderByNameAsc(user.id(), scope).stream().map(SavedFilterService::toDto).toList();
    }

    /** Saves a filter; saving with an existing name replaces that filter. */
    @Transactional
    public SavedFilterDto save(SavedFilterRequest req, AuthUser user) {
        requireScope(req.scope());
        validateJson(req.filter());
        String name = req.name().trim();
        if (name.isEmpty()) throw ApiException.badRequest("Give the saved filter a name.");
        SavedFilter f = filters.findByUser_IdAndScopeAndNameIgnoreCase(user.id(), req.scope(), name).orElse(null);
        if (f == null) {
            if (filters.countByUser_Id(user.id()) >= MAX_PER_USER) {
                throw ApiException.conflict("You can keep up to " + MAX_PER_USER + " saved filters. Delete one first.");
            }
            f = new SavedFilter();
            f.setUser(users.getReferenceById(user.id()));
            f.setScope(req.scope());
        }
        f.setName(name);
        f.setFilterJson(req.filter());
        return toDto(filters.save(f));
    }

    @Transactional
    public void delete(Long id, AuthUser user) {
        SavedFilter f = filters.findByIdAndUser_Id(id, user.id()).orElseThrow(() -> ApiException.notFound("Saved filter"));
        filters.delete(f);
    }

    private void requireScope(String scope) {
        if (scope == null || !SCOPES.contains(scope)) throw ApiException.badRequest("Scope must be referrals or hospitals.");
    }

    private void validateJson(String json) {
        try {
            JsonNode n = mapper.readTree(json);
            if (n == null || !n.isObject()) throw ApiException.badRequest("The filter must be a JSON object.");
            n.fields().forEachRemaining(e -> {
                if (!e.getValue().isValueNode()) throw ApiException.badRequest("Filter values must be simple text, numbers or booleans.");
            });
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw ApiException.badRequest("The filter is not valid JSON.");
        }
    }

    private static SavedFilterDto toDto(SavedFilter f) {
        return new SavedFilterDto(f.getId(), f.getName(), f.getScope(), f.getFilterJson(), f.getCreatedAt());
    }
}
