package com.pulseops.common.mail;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/** SMTP delivery through Spring's {@link JavaMailSender} (configured via SPRING_MAIL_* variables). */
public class SmtpMailSender implements MailSenderPort {

    private final JavaMailSender javaMailSender;
    private final String from;

    public SmtpMailSender(JavaMailSender javaMailSender, String from) {
        this.javaMailSender = javaMailSender;
        this.from = from;
    }

    @Override
    public void send(OutboundMail mail) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(mail.to());
        message.setSubject(mail.subject());
        message.setText(mail.body());
        javaMailSender.send(message);
    }
}
