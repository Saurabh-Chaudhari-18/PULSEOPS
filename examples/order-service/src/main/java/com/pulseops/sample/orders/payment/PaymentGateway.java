package com.pulseops.sample.orders.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Simulated payment provider: declines charges above a configured limit. */
@Component
public class PaymentGateway {

    public static class PaymentDeclinedException extends RuntimeException {
        public PaymentDeclinedException(String message) {
            super(message);
        }
    }

    private final long limitCents;

    public PaymentGateway(@Value("${sample.payment.limit-cents:100000}") long limitCents) {
        this.limitCents = limitCents;
    }

    public void charge(String customerEmail, long amountCents) {
        if (amountCents > limitCents) {
            throw new PaymentDeclinedException("Card issuer declined a charge of " + amountCents
                    + " cents (limit " + limitCents + ")");
        }
    }
}
