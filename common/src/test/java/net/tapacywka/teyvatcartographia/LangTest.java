package net.tapacywka.teyvatcartographia;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The English and Russian texts: the same keys, the same placeholders, all three plural forms, no long or short dash
 * (the user's rule), and every key the code names is there.
 */
class LangTest {
    private static final Path LANG = Path.of(System.getProperty("teyvatcartographia.projectDir", ".")).resolve("src/main/resources/assets/teyvatcartographia/lang");
    private static final Pattern PLACEHOLDER = Pattern.compile("%(\\d+\\$)?[sd]");
    private static final Pattern KEY_IN_CODE = Pattern.compile("\"((?:key\\.(?:categories\\.)?)?teyvatcartographia\\.[a-z0-9_.]*[a-z0-9_])\"");
    private static JsonObject en, ru;

    @BeforeAll
    static void load() throws IOException {
        en = JsonParser.parseString(Files.readString(LANG.resolve("en_us.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        ru = JsonParser.parseString(Files.readString(LANG.resolve("ru_ru.json"), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    void bothLanguagesHaveTheSameKeys() {
        Set<String> onlyEn = new TreeSet<>(en.keySet()), onlyRu = new TreeSet<>(ru.keySet());
        onlyEn.removeAll(ru.keySet());
        onlyRu.removeAll(en.keySet());
        assertTrue(onlyEn.isEmpty(), "missing in Russian: " + onlyEn);
        assertTrue(onlyRu.isEmpty(), "missing in English: " + onlyRu);
    }

    @Test
    void placeholdersMatch() {
        List<String> wrong = new ArrayList<>();
        for (String k : en.keySet()) {
            if (!ru.has(k)) continue;
            if (placeholders(en.get(k).getAsString()) != placeholders(ru.get(k).getAsString())) wrong.add(k);
        }
        assertTrue(wrong.isEmpty(), "placeholders differ: " + wrong);
    }

    private static int placeholders(String s) {
        Matcher m = PLACEHOLDER.matcher(s);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    @Test
    void pluralTextsHaveAllThreeForms() {
        List<String> wrong = new ArrayList<>();
        for (String k : en.keySet()) {
            if (!k.endsWith(".one")) continue;
            String base = k.substring(0, k.length() - 4);
            for (String form : new String[]{".few", ".many"}) if (!en.has(base + form) || !ru.has(base + form)) wrong.add(base + form);
        }
        assertTrue(wrong.isEmpty(), "missing plural forms: " + wrong);
    }

    @Test
    void noLongOrShortDash() {
        List<String> wrong = new ArrayList<>();
        for (JsonObject lang : new JsonObject[]{en, ru}) {
            for (String k : lang.keySet()) {
                String v = lang.get(k).getAsString();
                if (v.indexOf('—') >= 0 || v.indexOf('–') >= 0) wrong.add(k);
            }
        }
        assertTrue(wrong.isEmpty(), "a dash in: " + wrong);
    }

    @Test
    void noEmptyTexts() {
        List<String> wrong = new ArrayList<>();
        for (JsonObject lang : new JsonObject[]{en, ru}) {
            for (String k : lang.keySet()) if (lang.get(k).getAsString().isBlank()) wrong.add(k);
        }
        assertTrue(wrong.isEmpty(), "empty texts: " + wrong);
    }

    @Test
    void everyKeyTheCodeNamesExists() throws IOException {
        Set<String> missing = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of(System.getProperty("teyvatcartographia.projectDir", ".")).resolve("src/main/java"))) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java") && !p.toString().contains("devtest"))::iterator) {
                Matcher m = KEY_IN_CODE.matcher(Files.readString(f, StandardCharsets.UTF_8));
                while (m.find()) {
                    String key = m.group(1);
                    if (key.startsWith("teyvatcartographia.devtest")) continue; // system properties of the development tests
                    // The start of keys built in the code (dimension names: Util.makeDescriptionId) is not a key itself.
                    boolean prefix = en.keySet().stream().anyMatch(k -> k.startsWith(key + "."));
                    if (!en.has(key) && !prefix) missing.add(key + " (" + f.getFileName() + ")");
                }
            }
        }
        assertTrue(missing.isEmpty(), "keys named in the code but missing in en_us.json: " + missing);
    }

    @Test
    void theRussianDistanceFormsAreRussian() {
        assertEquals("%s блок", ru.get("teyvatcartographia.distance.blocks.one").getAsString());
        assertEquals("%s блока", ru.get("teyvatcartographia.distance.blocks.few").getAsString());
        assertEquals("%s блоков", ru.get("teyvatcartographia.distance.blocks.many").getAsString());
    }
}
