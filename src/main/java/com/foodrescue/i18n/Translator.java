package com.foodrescue.i18n;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foodrescue.domain.User;
import com.foodrescue.push.PushMessage;
import com.foodrescue.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Translates server-made text (push notifications) with the same dictionaries the web page uses
 * (static/i18n/&lt;lang&gt;.json, built from src/main/i18n). Entries may contain {x} for any text and
 * {n} for a number; the parts are translated too when they are interface text. Text without a
 * translation stays in English.
 */
@Component
@Slf4j
public class Translator {

    private record Entry(Pattern pattern, String out) {
    }

    private record Dictionary(Map<String, String> exact, List<Entry> patterns) {
    }

    private static final Dictionary NONE = new Dictionary(Map.of(), List.of());
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(x|n|\\d)}");

    private final Map<String, Dictionary> dictionaries = new ConcurrentHashMap<>();
    private final ObjectMapper json;
    private final UserRepository userRepository;

    public Translator(ObjectMapper json, UserRepository userRepository) {
        this.json = json;
        this.userRepository = userRepository;
    }

    /** The text in the language, or the text itself when there is no translation. */
    public String translate(String language, String text) {
        if (text == null || language == null || "en".equals(language)) return text;
        String result = lookup(dictionary(language), text.strip(), 0);
        return result == null ? text : result;
    }

    /** Push messages in each recipient's own language. */
    public List<PushMessage> localize(List<PushMessage> messages) {
        if (messages.isEmpty()) return messages;
        Map<Long, String> languages = userRepository.findAllById(
                        messages.stream().map(PushMessage::userId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(User::getId, User::getLanguage));
        List<PushMessage> out = new ArrayList<>(messages.size());
        for (PushMessage m : messages) {
            String lang = languages.getOrDefault(m.userId(), "en");
            out.add(new PushMessage(m.userId(), translate(lang, m.title()), translate(lang, m.body()), m.url(), m.tag()));
        }
        return out;
    }

    private String lookup(Dictionary d, String text, int depth) {
        String hit = d.exact().get(text);
        if (hit != null) return hit;
        if (depth > 3) return null;
        for (Entry e : d.patterns()) {
            Matcher m = e.pattern().matcher(text);
            if (!m.matches()) continue;
            List<String> parts = new ArrayList<>();
            for (int i = 1; i <= m.groupCount(); i++) {
                String part = m.group(i);
                String translated = lookup(d, part, depth + 1);
                parts.add(translated == null ? part : translated);
            }
            int[] next = {0};
            return PLACEHOLDER.matcher(e.out()).replaceAll(r -> {
                String k = r.group(1);
                int index = "x".equals(k) || "n".equals(k) ? next[0]++ : Integer.parseInt(k) - 1;
                return Matcher.quoteReplacement(index < parts.size() ? parts.get(index) : "");
            });
        }
        return null;
    }

    private Dictionary dictionary(String language) {
        return dictionaries.computeIfAbsent(language, this::load);
    }

    private Dictionary load(String language) {
        ClassPathResource file = new ClassPathResource("static/i18n/" + language + ".json");
        if (!language.matches("[a-z]{2}") || !file.exists()) return NONE;
        try (InputStream in = file.getInputStream()) {
            Map<String, String> all = json.readValue(in, new TypeReference<HashMap<String, String>>() { });
            Map<String, String> exact = new HashMap<>();
            List<Map.Entry<String, String>> withParts = new ArrayList<>();
            all.forEach((k, v) -> {
                if (k.contains("{x}") || k.contains("{n}")) withParts.add(Map.entry(k, v));
                else exact.put(k, v);
            });
            // More literal text first, as on the page.
            withParts.sort(Comparator.comparingInt((Map.Entry<String, String> e) -> -e.getKey().replaceAll("\\{[xn]}", "").length()));
            List<Entry> patterns = new ArrayList<>();
            for (Map.Entry<String, String> e : withParts) {
                StringBuilder regex = new StringBuilder("^");
                for (String piece : e.getKey().split("(?=\\{[xn]})|(?<=\\{[xn]})")) {
                    if (piece.equals("{x}")) regex.append("(.+?)");
                    else if (piece.equals("{n}")) regex.append("(\\d[\\d.,]*)");
                    else regex.append(Pattern.quote(piece));
                }
                patterns.add(new Entry(Pattern.compile(regex.append('$').toString(), Pattern.DOTALL), e.getValue()));
            }
            return new Dictionary(exact, patterns);
        } catch (IOException e) {
            log.warn("Could not read translations for {}: {}", language, e.getMessage());
            return NONE;
        }
    }
}
