package com.pulseops.auth.service;

import com.pulseops.common.config.PulseOpsProperties;
import com.pulseops.common.mail.OutboundMail;

import org.springframework.stereotype.Component;

/** Builds the account emails. Links point at the web UI (PULSEOPS_PUBLIC_URL). */
@Component
public class AuthEmails {

    private final String publicUrl;

    public AuthEmails(PulseOpsProperties props) {
        String url = props.publicUrl();
        this.publicUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    OutboundMail verification(String to, String name, String token) {
        return new OutboundMail(to, "Verify your PulseOps email address", """
                Hi %s,

                Confirm your email address to activate your PulseOps account:

                %s/verify-email?token=%s

                This link expires soon and can be used once. If you did not create an account,
                you can ignore this email.
                """.formatted(name, publicUrl, token));
    }

    OutboundMail accountAlreadyExists(String to) {
        return new OutboundMail(to, "PulseOps sign-up attempt", """
                Someone tried to create a PulseOps account with this email address, but an
                account already exists. If this was you, sign in or reset your password:

                %s/forgot-password

                If it was not you, no action is needed.
                """.formatted(publicUrl));
    }

    OutboundMail passwordReset(String to, String name, String token) {
        return new OutboundMail(to, "Reset your PulseOps password", """
                Hi %s,

                Use this link to choose a new password:

                %s/reset-password?token=%s

                The link expires soon and can be used once. Resetting signs you out of all
                sessions. If you did not request this, you can ignore this email.
                """.formatted(name, publicUrl, token));
    }
}
