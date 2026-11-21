package com.pulseops.common.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Development email mode: writes the message (including verification/reset links) to the
 * application log instead of sending it. Only ever selected when
 * {@code pulseops.mail.dev-log-only=true}, which is set exclusively by the dev profile.
 */
public class DevLogMailSender implements MailSenderPort {

    private static final Logger log = LoggerFactory.getLogger(DevLogMailSender.class);

    @Override
    public void send(OutboundMail mail) {
        log.info("""

                ======== DEV MAIL (not sent) ========
                To:      {}
                Subject: {}
                {}
                =====================================""", mail.to(), mail.subject(), mail.body());
    }
}
