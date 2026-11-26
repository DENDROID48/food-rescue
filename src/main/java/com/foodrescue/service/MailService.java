package com.foodrescue.service;

import com.foodrescue.config.AccountProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Sends the few emails the app needs, through whatever mail server is configured
 * (SPRING_MAIL_HOST and friends, see README). No mail server is needed to run the app: without
 * one, "forgot password" is simply not offered.
 *
 * <p>Emails go out in the background, so a slow mail server never delays the page, and the
 * time a request takes does not reveal whether an email address has an account.</p>
 */
@Service
@Slf4j
public class MailService {

    /** Subject and body per language; {name} and {link} are filled in. */
    record Text(String subject, String body) {
    }

    private static final Map<String, Text> RESET = Map.of(
            "en", new Text("Reset your Food Rescue password",
                    "Hello {name},\n\nSomeone asked to reset the password of your Food Rescue account. To choose a new password, open this link within the next hour:\n\n{link}\n\nIf this wasn't you, ignore this email: your password stays the same.\n\nFood Rescue"),
            "ro", new Text("Resetează-ți parola Food Rescue",
                    "Bună, {name},\n\nCineva a cerut resetarea parolei contului tău Food Rescue. Ca să alegi o parolă nouă, deschide acest link în următoarea oră:\n\n{link}\n\nDacă nu ai fost tu, ignoră acest email: parola ta rămâne aceeași.\n\nFood Rescue"),
            "es", new Text("Restablece tu contraseña de Food Rescue",
                    "Hola, {name}:\n\nAlguien ha pedido restablecer la contraseña de tu cuenta de Food Rescue. Para elegir una contraseña nueva, abre este enlace en la próxima hora:\n\n{link}\n\nSi no has sido tú, ignora este correo: tu contraseña no cambia.\n\nFood Rescue"),
            "fr", new Text("Réinitialisez votre mot de passe Food Rescue",
                    "Bonjour {name},\n\nQuelqu'un a demandé à réinitialiser le mot de passe de votre compte Food Rescue. Pour choisir un nouveau mot de passe, ouvrez ce lien dans l'heure qui suit :\n\n{link}\n\nSi ce n'était pas vous, ignorez cet e-mail : votre mot de passe reste le même.\n\nFood Rescue"),
            "de", new Text("Food-Rescue-Passwort zurücksetzen",
                    "Hallo {name},\n\njemand hat das Zurücksetzen des Passworts deines Food-Rescue-Kontos angefordert. Um ein neues Passwort zu wählen, öffne diesen Link innerhalb der nächsten Stunde:\n\n{link}\n\nWenn du das nicht warst, ignoriere diese E-Mail: Dein Passwort bleibt unverändert.\n\nFood Rescue"),
            "it", new Text("Reimposta la tua password di Food Rescue",
                    "Ciao {name},\n\nqualcuno ha chiesto di reimpostare la password del tuo account Food Rescue. Per sceglierne una nuova, apri questo link entro un'ora:\n\n{link}\n\nSe non sei stato tu, ignora questa email: la tua password resta la stessa.\n\nFood Rescue"),
            "pt", new Text("Redefina a sua palavra-passe do Food Rescue",
                    "Olá, {name},\n\nAlguém pediu para redefinir a palavra-passe da sua conta Food Rescue. Para escolher uma nova, abra este link na próxima hora:\n\n{link}\n\nSe não foi você, ignore este email: a sua palavra-passe continua igual.\n\nFood Rescue"));

    private static final Map<String, Text> CHANGED = Map.of(
            "en", new Text("Your Food Rescue password was changed",
                    "Hello {name},\n\nThe password of your Food Rescue account was just changed, and other devices were signed out.\n\nIf this wasn't you, reset your password straight away at {link}\n\nFood Rescue"),
            "ro", new Text("Parola ta Food Rescue a fost schimbată",
                    "Bună, {name},\n\nParola contului tău Food Rescue tocmai a fost schimbată, iar celelalte dispozitive au fost deconectate.\n\nDacă nu ai fost tu, resetează-ți imediat parola la {link}\n\nFood Rescue"),
            "es", new Text("Se ha cambiado tu contraseña de Food Rescue",
                    "Hola, {name}:\n\nSe acaba de cambiar la contraseña de tu cuenta de Food Rescue y se ha cerrado la sesión en los demás dispositivos.\n\nSi no has sido tú, restablece tu contraseña ahora mismo en {link}\n\nFood Rescue"),
            "fr", new Text("Votre mot de passe Food Rescue a été modifié",
                    "Bonjour {name},\n\nLe mot de passe de votre compte Food Rescue vient d'être modifié, et vos autres appareils ont été déconnectés.\n\nSi ce n'était pas vous, réinitialisez tout de suite votre mot de passe sur {link}\n\nFood Rescue"),
            "de", new Text("Dein Food-Rescue-Passwort wurde geändert",
                    "Hallo {name},\n\ndas Passwort deines Food-Rescue-Kontos wurde gerade geändert, und andere Geräte wurden abgemeldet.\n\nWenn du das nicht warst, setze dein Passwort sofort zurück: {link}\n\nFood Rescue"),
            "it", new Text("La tua password di Food Rescue è stata cambiata",
                    "Ciao {name},\n\nla password del tuo account Food Rescue è appena stata cambiata e gli altri dispositivi sono stati disconnessi.\n\nSe non sei stato tu, reimposta subito la password su {link}\n\nFood Rescue"),
            "pt", new Text("A sua palavra-passe do Food Rescue foi alterada",
                    "Olá, {name},\n\nA palavra-passe da sua conta Food Rescue acabou de ser alterada e os outros dispositivos foram desligados.\n\nSe não foi você, redefina já a sua palavra-passe em {link}\n\nFood Rescue"));

    private final ObjectProvider<JavaMailSender> mailSender;
    private final AccountProperties properties;
    private final String mailHost;

    public MailService(ObjectProvider<JavaMailSender> mailSender, AccountProperties properties,
                       @Value("${spring.mail.host:}") String mailHost) {
        this.mailSender = mailSender;
        this.properties = properties;
        this.mailHost = mailHost;
    }

    /** True when emails can actually reach people (or, in development, the reset link is logged). */
    public boolean canSendResetLinks() {
        return sendsEmail() || properties.logResetLinks();
    }

    private boolean sendsEmail() {
        // An empty SPRING_MAIL_HOST (as docker-compose passes when unset) means no mail server.
        return !mailHost.isBlank() && mailSender.getIfAvailable() != null && !properties.mailFrom().isBlank();
    }

    public void sendPasswordReset(String to, String name, String language, String link) {
        if (!sendsEmail() && properties.logResetLinks()) {
            // Development only (see application-dev.yml): never enable this where real people sign up.
            log.info("Password reset link for {}: {}", to, link);
            return;
        }
        send(to, RESET, name, language, link);
    }

    public void sendPasswordChanged(String to, String name, String language) {
        send(to, CHANGED, name, language, properties.publicUrl() + "/#forgot");
    }

    private void send(String to, Map<String, Text> texts, String name, String language, String link) {
        if (!sendsEmail()) return;
        JavaMailSender sender = mailSender.getObject();
        Text text = texts.getOrDefault(language, texts.get("en"));
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.mailFrom());
        message.setTo(to);
        message.setSubject(text.subject());
        message.setText(text.body().replace("{name}", name).replace("{link}", link));
        CompletableFuture.runAsync(() -> {
            try {
                sender.send(message);
            } catch (RuntimeException e) {
                // The address is not logged: only that sending failed and why.
                log.warn("Could not send an email: {}", e.getMessage());
            }
        });
    }
}
