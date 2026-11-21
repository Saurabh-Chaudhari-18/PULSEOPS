package com.pulseops.common.mail;

import com.pulseops.common.config.PulseOpsProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Chooses the mail adapter and fails fast on unsafe configuration: if email verification
 * is required but neither SMTP nor the dev log mode is configured, nobody could ever
 * verify an account — so the application refuses to start and says why.
 */
@Configuration
public class MailConfig {

    private static final Logger log = LoggerFactory.getLogger(MailConfig.class);

    @Bean
    MailSenderPort mailSenderPort(PulseOpsProperties props, ObjectProvider<JavaMailSender> javaMailSender) {
        if (props.mail().devLogOnly()) {
            log.warn("Mail is in DEV LOG mode: emails are written to the log, not sent. Never use in production.");
            return new DevLogMailSender();
        }
        JavaMailSender smtp = javaMailSender.getIfAvailable();
        if (smtp != null) {
            return new SmtpMailSender(smtp, props.mail().from());
        }
        if (props.auth().requireEmailVerification()) {
            throw new IllegalStateException("""
                    Email verification is required (PULSEOPS_REQUIRE_EMAIL_VERIFICATION=true) but no SMTP \
                    server is configured. Set SPRING_MAIL_HOST (and SPRING_MAIL_PORT/USERNAME/PASSWORD), \
                    or explicitly set PULSEOPS_REQUIRE_EMAIL_VERIFICATION=false for a self-hosted install \
                    without email, or run with the 'dev' profile for local development.""");
        }
        log.warn("No SMTP configured and email verification disabled: password-reset emails cannot be delivered.");
        return mail -> {
            throw new IllegalStateException("No mail transport configured");
        };
    }
}
