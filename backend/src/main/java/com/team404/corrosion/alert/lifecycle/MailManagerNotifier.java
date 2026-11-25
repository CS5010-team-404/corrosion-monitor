package com.team404.corrosion.alert.lifecycle;

import com.team404.corrosion.alert.lifecycle.AlertViews.AlertView;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Emails the managers when an alert escalates. The SMTP server comes from {@code SPRING_MAIL_*} (MailHog locally, a
 * hosted SMTP service in production, see docs/DEPLOYMENT.md) and the sender address from
 * {@code ECP_ALERTS_MAIL_FROM}. Best effort: the escalation is already saved, so any failure is logged and nothing
 * else happens, and the SMTP timeouts in application.properties bound how long a send can take.
 */
@Component
public class MailManagerNotifier implements ManagerNotifier {

    private static final Logger log = LoggerFactory.getLogger(MailManagerNotifier.class);

    private final ObjectProvider<JavaMailSender> mail;
    private final String from;

    public MailManagerNotifier(ObjectProvider<JavaMailSender> mail,
                               @Value("${ecp.alerts.mail-from:alerts@corrosionwatch.app}") String from) {
        this.mail = mail;
        this.from = from;
    }

    @Override
    public void escalated(AlertView alert, List<String> managerEmails) {
        try {
            JavaMailSender sender = mail.getIfAvailable();
            if (sender == null || managerEmails.isEmpty()) {
                log.warn("Alert {} escalated but nobody was emailed (mail sender: {}, managers: {})", alert.id(),
                        sender != null, managerEmails.size());
                return;
            }
            sender.send(message(alert, managerEmails));
            log.info("Emailed {} manager(s) about escalated alert {}", managerEmails.size(), alert.id());
        } catch (RuntimeException e) {
            log.warn("Could not email managers about alert {}: {}", alert.id(), e.toString());
        }
    }

    SimpleMailMessage message(AlertView alert, List<String> managerEmails) {
        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(managerEmails.toArray(String[]::new));
        message.setSubject("Escalated: " + alert.title());
        message.setText("""
                Critical alert %d was not acknowledged and has escalated to you.

                %s
                Equipment: %s (%s)
                Opened: %s
                """.formatted(alert.id(), alert.message() == null ? alert.title() : alert.message(),
                alert.equipmentName() == null ? "n/a" : alert.equipmentName(),
                alert.locationName() == null ? "n/a" : alert.locationName(), alert.openedAt()));
        return message;
    }
}
