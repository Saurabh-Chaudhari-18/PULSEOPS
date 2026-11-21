package com.pulseops.common.mail;

/**
 * Outbound email adapter. Implementations: {@link DevLogMailSender} (dev profile only)
 * and {@link SmtpMailSender}. Alert notifications (Phase 15) reuse this port.
 */
public interface MailSenderPort {

    void send(OutboundMail mail);
}
