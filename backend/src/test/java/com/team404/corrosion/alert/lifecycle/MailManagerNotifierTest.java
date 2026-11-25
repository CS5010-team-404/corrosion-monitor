package com.team404.corrosion.alert.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.team404.corrosion.alert.lifecycle.AlertViews.AlertView;
import jakarta.mail.internet.MimeMessage;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * The escalation email end to end against an in-memory SMTP server (GreenMail), with the mail settings read from the
 * real application.properties and the environment variables Render sets (SPRING_MAIL_*, ECP_ALERTS_MAIL_FROM).
 */
class MailManagerNotifierTest {

    private static final String SMTP_USER = "smtp-user";
    private static final String SMTP_PASSWORD = "smtp-secret";

    @RegisterExtension
    static final GreenMailExtension SMTP = new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort())
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("alerts@test.local", SMTP_USER, SMTP_PASSWORD));

    private static final AlertView ALERT = new AlertView(42, "CONDENSATION_RISK", "ANALYTICS", "CRITICAL",
            AlertStatus.OPEN, 1, 7L, "Rack A1", "Server Room A", null, null,
            "Condensation risk: Rack A1, Server Room A", "Margin 0.4 C below the critical threshold",
            Instant.parse("2026-11-06T12:00:00Z"), null, null, null, null, null);

    @Test
    void readsTheSmtpSettingsAndTimeoutsFromTheEnvironment() {
        runner("smtp.example.com", 2525, "apikey", "pw").run(context -> {
            var sender = (JavaMailSenderImpl) context.getBean(org.springframework.mail.javamail.JavaMailSender.class);
            assertThat(sender.getHost()).isEqualTo("smtp.example.com");
            assertThat(sender.getPort()).isEqualTo(2525);
            assertThat(sender.getUsername()).isEqualTo("apikey");
            assertThat(sender.getPassword()).isEqualTo("pw");
            assertThat(sender.getJavaMailProperties())
                    .containsEntry("mail.smtp.connectiontimeout", "5000")
                    .containsEntry("mail.smtp.timeout", "10000")
                    .containsEntry("mail.smtp.writetimeout", "10000")
                    .containsEntry("mail.smtp.starttls.enable", "true");
        });
    }

    @Test
    void emailsTheManagersThroughTheConfiguredServer() {
        runner("127.0.0.1", SMTP.getSmtp().getPort(), SMTP_USER, SMTP_PASSWORD).run(context -> {
            context.getBean(MailManagerNotifier.class).escalated(ALERT, List.of("max@test.local", "mia@test.local"));

            MimeMessage[] received = SMTP.getReceivedMessages();
            assertThat(received).hasSize(2);   // one copy per recipient
            MimeMessage mail = received[0];
            assertThat(mail.getSubject()).isEqualTo("Escalated: Condensation risk: Rack A1, Server Room A");
            assertThat(mail.getFrom()[0].toString()).isEqualTo("alerts@test.local");
            assertThat(List.of(mail.getAllRecipients()).stream().map(Object::toString))
                    .containsExactlyInAnyOrder("max@test.local", "mia@test.local");
            assertThat(GreenMailUtil.getBody(mail))
                    .contains("Critical alert 42")
                    .contains("Equipment: Rack A1 (Server Room A)")
                    .contains("Margin 0.4 C below the critical threshold");
        });
    }

    @Test
    void anUnreachableServerIsLoggedNotThrown() throws Exception {
        int closedPort;
        try (var socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        runner("127.0.0.1", closedPort, "", "").run(context -> {
            long start = System.nanoTime();
            assertThatCode(() -> context.getBean(MailManagerNotifier.class).escalated(ALERT, List.of("max@test.local")))
                    .doesNotThrowAnyException();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(15));
        });
        assertThat(SMTP.getReceivedMessages()).isEmpty();
    }

    @Test
    void nobodyIsEmailedWithoutManagers() {
        runner("127.0.0.1", SMTP.getSmtp().getPort(), SMTP_USER, SMTP_PASSWORD).run(context ->
                context.getBean(MailManagerNotifier.class).escalated(ALERT, List.of()));
        assertThat(SMTP.getReceivedMessages()).isEmpty();
    }

    /** application.properties plus the environment variables, as the backend reads them on Render. */
    private static ApplicationContextRunner runner(String host, int port, String user, String password) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
                .withUserConfiguration(MailManagerNotifier.class)
                .withSystemProperties("SPRING_MAIL_HOST=" + host, "SPRING_MAIL_PORT=" + port,
                        "SPRING_MAIL_USERNAME=" + user, "SPRING_MAIL_PASSWORD=" + password,
                        "ECP_ALERTS_MAIL_FROM=alerts@test.local");
    }
}
