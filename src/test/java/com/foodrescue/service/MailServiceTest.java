package com.foodrescue.service;

import com.foodrescue.config.AccountProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MailServiceTest {

    @SuppressWarnings("unchecked")
    private MailService mail(String host, String user, String password, String from, boolean enabled) {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(host.isBlank() ? null : mock(JavaMailSender.class));
        return new MailService(provider, new AccountProperties(null, from, false, true), host, user, password, enabled);
    }

    @Test
    void emailIsOnlyOfferedOnceEverythingIsFilledIn() {
        assertThat(mail("", "", "", "", true).canSendResetLinks()).isFalse();
        // A server that needs a login: without the SMTP key nothing would arrive.
        assertThat(mail("smtp.example.org", "login@smtp.example.org", "", "Food Rescue <a@b.ro>", true).canSendResetLinks()).isFalse();
        assertThat(mail("smtp.example.org", "login@smtp.example.org", "key", "", true).canSendResetLinks()).isFalse();
        assertThat(mail("smtp.example.org", "login@smtp.example.org", "key", "Food Rescue <a@b.ro>", true).canSendResetLinks()).isTrue();
        // A server without a login (for example on the same machine) needs no password.
        assertThat(mail("localhost", "", "", "Food Rescue <a@b.ro>", true).canSendResetLinks()).isTrue();
        // Switched off (the tests): never.
        assertThat(mail("smtp.example.org", "login@smtp.example.org", "key", "Food Rescue <a@b.ro>", false).canSendResetLinks()).isFalse();
    }
}
