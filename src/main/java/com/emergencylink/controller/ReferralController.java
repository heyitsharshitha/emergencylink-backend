package com.emergencylink.controller;

import com.emergencylink.domain.ReferralStatus;
import com.emergencylink.domain.Urgency;
import com.emergencylink.dto.CommonDtos.PageResponse;
import com.emergencylink.dto.ReferralDtos.*;
import com.emergencylink.security.CurrentUser;
import com.emergencylink.service.ReferralService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/referrals")
public class ReferralController {
    private final ReferralService referrals;
    public ReferralController(ReferralService referrals) { this.referrals = referrals; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('REFERRING_HOSPITAL')")
    public ReferralDto create(@Valid @RequestBody ReferralCreateRequest req) { return referrals.create(req, CurrentUser.get()); }

    @GetMapping
    public PageResponse<ReferralDto> list(@RequestParam(required = false) String q,
                                          @RequestParam(required = false) ReferralStatus status,
                                          @RequestParam(required = false) Urgency urgency,
                                          @RequestParam(required = false) String specialty,
                                          @RequestParam(required = false) String hospital,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                          @RequestParam(required = false) String view,
                                          @RequestParam(required = false) String sort,
                                          @RequestParam(required = false) String dir,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "10") int size) {
        return referrals.list(CurrentUser.get(), q, status, urgency, specialty, hospital, from, to, view, sort, dir, page, size);
    }

    @GetMapping("/{id}")
    public ReferralDto get(@PathVariable Long id) { return referrals.get(id, CurrentUser.get()); }

    @GetMapping("/{id}/timeline")
    public List<TimelineEventDto> timeline(@PathVariable Long id) { return referrals.timeline(id, CurrentUser.get()); }

    /** Read-only preview of rule-based compatibility results; does not change the referral. */
    @GetMapping("/{id}/match")
    public List<MatchResultDto> preview(@PathVariable Long id) { return referrals.previewMatches(id, CurrentUser.get()); }

    /** Runs matching and moves the referral into MATCHING. */
    @PostMapping("/{id}/match")
    @PreAuthorize("hasRole('REFERRING_HOSPITAL')")
    public List<MatchResultDto> match(@PathVariable Long id) { return referrals.runMatching(id, CurrentUser.get()); }

    @PostMapping("/{id}/send")
    @PreAuthorize("hasRole('REFERRING_HOSPITAL')")
    public ReferralDto send(@PathVariable Long id, @Valid @RequestBody SendRequest req) { return referrals.send(id, req, CurrentUser.get()); }

    @PostMapping("/{id}/provide-info")
    @PreAuthorize("hasRole('REFERRING_HOSPITAL')")
    public ReferralDto provideInfo(@PathVariable Long id, @Valid @RequestBody RequiredNoteRequest req) {
        return referrals.provideInformation(id, req, CurrentUser.get());
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('REFERRING_HOSPITAL')")
    public ReferralDto cancel(@PathVariable Long id, @Valid @RequestBody(required = false) NoteRequest req) {
        return referrals.cancel(id, req, CurrentUser.get());
    }

    @PostMapping("/{id}/view")
    @PreAuthorize("hasRole('RECEIVING_HOSPITAL')")
    public ReferralDto view(@PathVariable Long id) { return referrals.markViewed(id, CurrentUser.get()); }

    @PostMapping("/{id}/accept")
    @PreAuthorize("hasRole('RECEIVING_HOSPITAL')")
    public ReferralDto accept(@PathVariable Long id, @Valid @RequestBody(required = false) NoteRequest req) {
        return referrals.accept(id, req, CurrentUser.get());
    }

    @PostMapping("/{id}/decline")
    @PreAuthorize("hasRole('RECEIVING_HOSPITAL')")
    public ReferralDto decline(@PathVariable Long id, @Valid @RequestBody RequiredNoteRequest req) {
        return referrals.decline(id, req, CurrentUser.get());
    }

    @PostMapping("/{id}/request-info")
    @PreAuthorize("hasRole('RECEIVING_HOSPITAL')")
    public ReferralDto requestInfo(@PathVariable Long id, @Valid @RequestBody RequiredNoteRequest req) {
        return referrals.requestInformation(id, req, CurrentUser.get());
    }

    @PostMapping("/{id}/start-transfer")
    @PreAuthorize("hasAnyRole('REFERRING_HOSPITAL','RECEIVING_HOSPITAL')")
    public ReferralDto startTransfer(@PathVariable Long id) { return referrals.startTransfer(id, CurrentUser.get()); }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAnyRole('REFERRING_HOSPITAL','RECEIVING_HOSPITAL')")
    public ReferralDto complete(@PathVariable Long id) { return referrals.complete(id, CurrentUser.get()); }

    @GetMapping("/{id}/messages")
    public List<MessageDto> messages(@PathVariable Long id) { return referrals.messages(id, CurrentUser.get()); }

    @PostMapping("/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('REFERRING_HOSPITAL','RECEIVING_HOSPITAL')")
    public MessageDto postMessage(@PathVariable Long id, @Valid @RequestBody MessageRequest req) {
        return referrals.postMessage(id, req, CurrentUser.get());
    }
}
