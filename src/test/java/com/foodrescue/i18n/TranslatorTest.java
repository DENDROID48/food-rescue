package com.foodrescue.i18n;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foodrescue.domain.User;
import com.foodrescue.push.PushMessage;
import com.foodrescue.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TranslatorTest {

    private final UserRepository users = mock(UserRepository.class);
    private final Translator translator = new Translator(new ObjectMapper(), users);

    @Test
    void translatesWholeTextAndTextWithChangingParts() {
        assertThat(translator.translate("ro", "Rescue Drop at Corner Bakery")).isEqualTo("Rescue Drop la Corner Bakery");
        // The parts are translated too: the drop description inside "… · until 19:30".
        assertThat(translator.translate("ro", "3 × Bread for 9.00 lei · until 19:30"))
                .isEqualTo("3 × Pâine la 9.00 lei · până la 19:30");
        assertThat(translator.translate("de", "2 offers near you today")).isEqualTo("2 Angebote heute in deiner Nähe");
        assertThat(translator.translate("fr", "Pick up by 19:30 at Corner Bakery")).isEqualTo("À récupérer avant 19:30 chez Corner Bakery");
    }

    @Test
    void numbersOnlyMatchNumbersAndUnknownTextStaysAsItIs() {
        // "{n} of {n}" must not turn a product called "Box of chocolates" into something else.
        assertThat(translator.translate("ro", "Box of chocolates")).isEqualTo("Box of chocolates");
        assertThat(translator.translate("ro", "3 of 5")).isEqualTo("3 din 5");
        assertThat(translator.translate("en", "Rescue Drop at X")).isEqualTo("Rescue Drop at X");
        assertThat(translator.translate("xx", "Rescue Drop at X")).isEqualTo("Rescue Drop at X");
    }

    @Test
    void pushMessagesUseEachRecipientsLanguage() {
        User ana = new User();
        ana.setId(1L);
        ana.setLanguage("es");
        User bob = new User();
        bob.setId(2L);
        when(users.findAllById(any())).thenReturn(List.of(ana, bob));
        List<PushMessage> out = translator.localize(List.of(
                new PushMessage(1L, "1 offer near you today", "x", "/", "t"),
                new PushMessage(2L, "1 offer near you today", "x", "/", "t")));
        assertThat(out.get(0).title()).isEqualTo("1 oferta cerca de ti hoy");
        assertThat(out.get(1).title()).isEqualTo("1 offer near you today");
    }

    /** The page's files must be rebuilt (node src/main/i18n/build.js) whenever the translations change. */
    @Test
    void builtLanguageFilesMatchTheSourceTranslations() throws IOException {
        Path dir = Path.of("src/main/i18n");
        long keys = Files.readAllLines(dir.resolve("keys.txt")).stream().filter(l -> l.matches("^\\d+\\|.*")).count();
        for (String lang : List.of("ro", "es", "fr", "de", "it", "pt")) {
            Map<?, ?> built = new ObjectMapper().readValue(Path.of("src/main/resources/static/i18n", lang + ".json").toFile(), Map.class);
            assertThat(built).as(lang + ".json is out of date: run node src/main/i18n/build.js").hasSize((int) keys);
        }
    }
}
