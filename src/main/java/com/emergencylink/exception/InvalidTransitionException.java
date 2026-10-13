package com.emergencylink.exception;

import com.emergencylink.domain.ReferralStatus;
import org.springframework.http.HttpStatus;

public class InvalidTransitionException extends ApiException {
    public InvalidTransitionException(ReferralStatus from, ReferralStatus to) {
        super(HttpStatus.CONFLICT, "A referral in status " + from + " cannot move to " + to + ".");
    }
}
