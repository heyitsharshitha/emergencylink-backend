package com.emergencylink;

import com.emergencylink.domain.ReferralStatus;
import org.junit.jupiter.api.Test;
import static com.emergencylink.domain.ReferralStatus.*;
import static org.junit.jupiter.api.Assertions.*;

class ReferralStatusTest {
    @Test void happyPathIsAllowed() {
        assertTrue(CREATED.canMoveTo(MATCHING));
        assertTrue(MATCHING.canMoveTo(SENT));
        assertTrue(SENT.canMoveTo(VIEWED));
        assertTrue(VIEWED.canMoveTo(ACCEPTED));
        assertTrue(ACCEPTED.canMoveTo(TRANSFER_IN_PROGRESS));
        assertTrue(TRANSFER_IN_PROGRESS.canMoveTo(COMPLETED));
    }

    @Test void invalidJumpsAreRejected() {
        assertFalse(CREATED.canMoveTo(ACCEPTED));
        assertFalse(SENT.canMoveTo(ACCEPTED));
        assertFalse(COMPLETED.canMoveTo(CREATED));
        assertFalse(DECLINED.canMoveTo(ACCEPTED));
    }

    @Test void declinedReferralCanBeRematched() { assertTrue(DECLINED.canMoveTo(MATCHING)); }

    @Test void terminalStatesHaveNoExit() {
        for (ReferralStatus s : new ReferralStatus[]{COMPLETED, CANCELLED}) {
            assertTrue(s.isTerminal());
            assertTrue(s.allowedNext().isEmpty());
        }
    }
}
