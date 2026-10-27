package net.tapacywka.teyvatcartographia.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** "1 блок, 2 блока, 5 блоков": the plural form of a distance. */
class DistanceTextTest {
    @Test
    void russianForms() {
        assertEquals("one", DistanceText.pluralForm(1, "ru_ru"));
        assertEquals("few", DistanceText.pluralForm(2, "ru_ru"));
        assertEquals("few", DistanceText.pluralForm(4, "ru_ru"));
        assertEquals("many", DistanceText.pluralForm(5, "ru_ru"));
        assertEquals("many", DistanceText.pluralForm(0, "ru_ru"));
        assertEquals("many", DistanceText.pluralForm(11, "ru_ru"));
        assertEquals("many", DistanceText.pluralForm(12, "ru_ru"));
        assertEquals("many", DistanceText.pluralForm(14, "ru_ru"));
        assertEquals("one", DistanceText.pluralForm(21, "ru_ru"));
        assertEquals("few", DistanceText.pluralForm(22, "ru_ru"));
        assertEquals("many", DistanceText.pluralForm(111, "ru_ru"));
        assertEquals("one", DistanceText.pluralForm(101, "ru_ru"));
    }

    @Test
    void otherEastSlavicLanguagesUseTheSameRules() {
        assertEquals("few", DistanceText.pluralForm(3, "uk_ua"));
        assertEquals("one", DistanceText.pluralForm(31, "be_by"));
    }

    @Test
    void englishAndOthers() {
        assertEquals("one", DistanceText.pluralForm(1, "en_us"));
        assertEquals("many", DistanceText.pluralForm(2, "en_us"));
        assertEquals("many", DistanceText.pluralForm(0, "de_de"));
    }

    @Test
    void negativeCountsUseTheirSize() {
        assertEquals("one", DistanceText.pluralForm(-1, "ru_ru"));
        assertEquals("few", DistanceText.pluralForm(-3, "ru_ru"));
    }
}
