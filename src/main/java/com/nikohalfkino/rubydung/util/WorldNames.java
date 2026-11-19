package com.nikohalfkino.rubydung.util;

import com.nikohalfkino.rubydung.level.tiles.model.Toml;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * random world names, built from /assets/rubydung/world_names.toml: one pattern ("The {adjective} {location} of {noun}")
 * with every {category} replaced by a random word of [words].category
 *
 * a missing or broken file never crashes the game: the problem is printed and a small built-in list is used
 */
public final class WorldNames {

    private static final String RESOURCE = "/assets/rubydung/world_names.toml";
    private static final String FALLBACK_NAME = "New World";
    private static final int ATTEMPTS = 60;

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_]+)\\}");

    private static final Random RANDOM = new Random();

    private static volatile Data data = load();

    private WorldNames() {
    }

    private static final class Data {
        final List<String> patterns;
        final Map<String, List<String>> words;

        Data(List<String> patterns, Map<String, List<String>> words) {
            this.patterns = patterns;
            this.words = words;
        }
    }

    /** reads world_names.toml again (after editing it while the game runs) */
    public static void reload() {
        data = load();
    }

    /**
     * a random name of at most maxLength characters that accept() likes (e.g. "no world has it yet"),
     * never empty. the rare case where nothing fits in ATTEMPTS tries gives the last one cut to size
     */
    public static String random(int maxLength, Predicate<String> accept) {
        Data current = data;
        String last = FALLBACK_NAME;

        for (int i = 0; i < ATTEMPTS; i++) {
            String name = generate(current);
            if (name.isEmpty()) {
                continue;
            }
            last = name;
            if (name.length() <= maxLength && (accept == null || accept.test(name))) {
                return name;
            }
        }

        return last.length() > maxLength ? last.substring(0, maxLength).trim() : last;
    }

    private static String generate(Data d) {
        String pattern = d.patterns.get(RANDOM.nextInt(d.patterns.size()));

        Matcher matcher = PLACEHOLDER.matcher(pattern);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            List<String> choices = d.words.get(matcher.group(1).toLowerCase(Locale.ROOT));
            matcher.appendReplacement(out, Matcher.quoteReplacement(choices.get(RANDOM.nextInt(choices.size()))));
        }
        matcher.appendTail(out);

        String name = out.toString().trim().replaceAll("\\s+", " ");
        return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    // ---- loading ----

    private static Data load() {
        try (InputStream in = WorldNames.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                System.err.println("world names: " + RESOURCE + " not found, using the built-in list");
                return builtIn();
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, n);
            }
            String text = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            if (text.startsWith("\uFEFF")) {
                text = text.substring(1);
            }

            Data parsed = fromToml(Toml.parse(text));
            if (parsed != null) {
                return parsed;
            }
        } catch (IOException e) {
            System.err.println("world names: could not read world_names.toml, using the built-in list: " + e);
        } catch (RuntimeException e) {
            System.err.println("world names: could not parse world_names.toml, using the built-in list: " + e.getMessage());
        }
        return builtIn();
    }

    // null if nothing usable is in the file
    private static Data fromToml(Map<String, Object> root) {
        Map<String, List<String>> words = new HashMap<>();
        Map<String, Object> wordTable = table(root, "words");
        if (wordTable != null) {
            for (Map.Entry<String, Object> entry : wordTable.entrySet()) {
                List<String> list = strings(entry.getValue());
                if (!list.isEmpty()) {
                    words.put(entry.getKey().toLowerCase(Locale.ROOT), list);
                }
            }
        }

        List<String> patterns = new ArrayList<>();
        for (String pattern : strings(table(root, "names") == null ? null : table(root, "names").get("patterns"))) {
            String missing = missingCategory(pattern, words);
            if (missing != null) {
                System.err.println("world names: pattern \"" + pattern + "\" skipped, there are no words for {" + missing + "}");
            } else if (!pattern.trim().isEmpty()) {
                patterns.add(pattern);
            }
        }

        if (patterns.isEmpty()) {
            System.err.println("world names: world_names.toml has no usable pattern");
            return null;
        }
        return new Data(Collections.unmodifiableList(patterns), words);
    }

    private static String missingCategory(String pattern, Map<String, List<String>> words) {
        Matcher matcher = PLACEHOLDER.matcher(pattern);
        while (matcher.find()) {
            if (!words.containsKey(matcher.group(1).toLowerCase(Locale.ROOT))) {
                return matcher.group(1);
            }
        }
        return null;
    }

    private static Data builtIn() {
        Map<String, List<String>> words = new HashMap<>();
        words.put("adjective", java.util.Arrays.asList("Silent", "Hollow", "Forgotten", "Mossy", "Distant", "Sunken"));
        words.put("location", java.util.Arrays.asList("Vale", "Cavern", "Isle", "Ruins", "Garden", "Reach"));
        words.put("noun", java.util.Arrays.asList("Echoes", "Embers", "Dust", "Lanterns", "the Deep", "the Moon"));
        return new Data(java.util.Arrays.asList("The {adjective} {location}", "{adjective} {location} of {noun}"), words);
    }

    // ---- typed access to the parsed toml ----

    @SuppressWarnings("unchecked")
    private static Map<String, Object> table(Map<String, Object> parent, String key) {
        Object value = parent == null ? null : parent.get(key);
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    /** a string or a list of strings, always a list */
    private static List<String> strings(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof String) {
            result.add((String) value);
        } else if (value instanceof List) {
            for (Object item : (List<?>) value) {
                if (item instanceof String && !((String) item).trim().isEmpty()) {
                    result.add((String) item);
                }
            }
        }
        return result;
    }
}
