package com.pulseops.common.mail;

/** A plain-text email. */
public record OutboundMail(String to, String subject, String body) {
}
