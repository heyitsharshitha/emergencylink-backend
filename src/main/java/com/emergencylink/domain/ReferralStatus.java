package com.emergencylink.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Referral lifecycle. The allowed transitions are the single source of truth for the backend state machine. */
public enum ReferralStatus {
    CREATED, MATCHING, SENT, VIEWED, MORE_INFORMATION_REQUIRED, ACCEPTED, DECLINED,
    TRANSFER_IN_PROGRESS, COMPLETED, CANCELLED;

    private static final Map<ReferralStatus, Set<ReferralStatus>> NEXT = new EnumMap<>(ReferralStatus.class);
    static {
        NEXT.put(CREATED, EnumSet.of(MATCHING, SENT, CANCELLED));
        NEXT.put(MATCHING, EnumSet.of(SENT, CANCELLED));
        NEXT.put(SENT, EnumSet.of(VIEWED, CANCELLED));
        NEXT.put(VIEWED, EnumSet.of(MORE_INFORMATION_REQUIRED, ACCEPTED, DECLINED, CANCELLED));
        NEXT.put(MORE_INFORMATION_REQUIRED, EnumSet.of(VIEWED, ACCEPTED, DECLINED, CANCELLED));
        NEXT.put(ACCEPTED, EnumSet.of(TRANSFER_IN_PROGRESS, CANCELLED));
        NEXT.put(DECLINED, EnumSet.of(MATCHING));
        NEXT.put(TRANSFER_IN_PROGRESS, EnumSet.of(COMPLETED));
        NEXT.put(COMPLETED, EnumSet.noneOf(ReferralStatus.class));
        NEXT.put(CANCELLED, EnumSet.noneOf(ReferralStatus.class));
    }

    public Set<ReferralStatus> allowedNext() { return NEXT.get(this); }
    public boolean canMoveTo(ReferralStatus target) { return NEXT.get(this).contains(target); }
    public boolean isTerminal() { return this == COMPLETED || this == CANCELLED; }
    /** Statuses in which the receiving hospital is not yet involved. */
    public boolean isPreDispatch() { return this == CREATED || this == MATCHING; }
}
