package com.pulseops.common.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Sends mail off the request thread. Besides latency, this keeps response timing identical
 * whether or not an email was sent — e.g. "forgot password" for an unknown address must not
 * be distinguishable from a known one.
 */
@Component
public class MailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MailDispatcher.class);

    private final MailSenderPort sender;

    public MailDispatcher(MailSenderPort sender) {
        this.sender = sender;
    }

    @Async
    public void dispatch(OutboundMail mail) {
        try {
            sender.send(mail);
        } catch (RuntimeException e) {
            // Never log the body: it may contain a one-time token.
            log.error("Failed to send email '{}' to {}", mail.subject(), mail.to(), e);
        }
    }
}
