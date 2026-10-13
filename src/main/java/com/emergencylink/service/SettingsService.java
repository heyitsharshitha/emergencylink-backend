package com.emergencylink.service;

import com.emergencylink.config.AppProperties;
import com.emergencylink.domain.SystemSetting;
import com.emergencylink.dto.AdminDtos.SettingsDto;
import com.emergencylink.dto.AdminDtos.SettingsUpdateRequest;
import com.emergencylink.exception.ApiException;
import com.emergencylink.repository.SystemSettingRepository;
import com.emergencylink.security.AuthUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

/**
 * Operational thresholds an administrator can change at runtime. Values fall back to configuration when never edited.
 * These are workflow and data-freshness settings, never clinical rules.
 */
@Service
public class SettingsService {
    public static final String WAIT_KEY = "referral-wait-minutes";
    public static final String STALE_KEY = "resource-stale-minutes";

    private final SystemSettingRepository repo;
    private final AuditService audit;
    private final long defaultWait;
    private final long defaultStale;
    private volatile Long waitCache;
    private volatile Long staleCache;

    public SettingsService(SystemSettingRepository repo, AuditService audit, AppProperties props,
                           @Value("${app.thresholds.referral-wait-minutes:30}") long defaultWait) {
        this.repo = repo;
        this.audit = audit;
        this.defaultWait = defaultWait;
        this.defaultStale = props.resources().staleAfterMinutes();
    }

    /** Minutes a dispatched referral may wait without an update before it is shown as overdue. */
    public long waitMinutes() {
        Long v = waitCache;
        if (v == null) { v = load(WAIT_KEY, defaultWait); waitCache = v; }
        return v;
    }

    /** Minutes after which a resource record is shown as stale and needs confirmation. */
    public long staleMinutes() {
        Long v = staleCache;
        if (v == null) { v = load(STALE_KEY, defaultStale); staleCache = v; }
        return v;
    }

    @Transactional(readOnly = true)
    public SettingsDto current() {
        String by = null;
        Instant at = null;
        for (String key : new String[] {WAIT_KEY, STALE_KEY}) {
            SystemSetting s = repo.findById(key).orElse(null);
            if (s != null && s.getUpdatedAt() != null && (at == null || s.getUpdatedAt().isAfter(at))) { at = s.getUpdatedAt(); by = s.getUpdatedBy(); }
        }
        return new SettingsDto(waitMinutes(), staleMinutes(), defaultWait, defaultStale, by, at);
    }

    @Transactional
    public SettingsDto update(SettingsUpdateRequest req, AuthUser admin) {
        long beforeWait = waitMinutes();
        long beforeStale = staleMinutes();
        if (beforeWait == req.waitMinutes() && beforeStale == req.staleMinutes()) throw ApiException.badRequest("No settings were changed.");
        save(WAIT_KEY, req.waitMinutes(), admin);
        save(STALE_KEY, req.staleMinutes(), admin);
        waitCache = req.waitMinutes();
        staleCache = req.staleMinutes();
        audit.record(admin, "SETTINGS_UPDATED", "Settings", null, "Operational thresholds", "Thresholds changed", req.reason().trim(),
                "referral wait " + beforeWait + " min; resource freshness " + beforeStale + " min",
                "referral wait " + req.waitMinutes() + " min; resource freshness " + req.staleMinutes() + " min");
        return current();
    }

    private void save(String key, long value, AuthUser admin) {
        SystemSetting s = repo.findById(key).orElseGet(() -> { SystemSetting n = new SystemSetting(); n.setKey(key); return n; });
        s.setValue(Long.toString(value));
        s.setUpdatedBy(admin.fullName());
        s.setUpdatedAt(Instant.now());
        repo.save(s);
    }

    private long load(String key, long fallback) {
        try {
            return repo.findById(key).map(s -> Long.parseLong(s.getValue())).orElse(fallback);
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
