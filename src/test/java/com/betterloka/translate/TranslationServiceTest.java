package com.betterloka.translate;

import com.betterloka.api.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The translation endpoint answers with a nested array rather than an object; this pins its shape. */
class TranslationServiceTest {

    @Test
    void joinsEverySentenceOfTheResponse() throws Exception {
        String body = "[[[\"ktos chetny na walke? \",\"anyone up for a fight? \",null,null,3],"
                + "[\"potrzebujemy jeszcze 3\",\"we need 3 more\",null,null,3]],null,\"en\"]";
        assertEquals("ktos chetny na walke? potrzebujemy jeszcze 3", TranslationService.parse(body).text());
    }

    @Test
    void toleratesASingleSentence() throws Exception {
        assertEquals("siema", TranslationService.parse("[[[\"siema\",\"hello\",null,null,3]],null,\"en\"]").text());
    }

    @Test
    void saysWhichLanguageTheOriginalWasIn() throws Exception {
        TranslationService.Translation result =
                TranslationService.parse("[[[\"hello\",\"siema\",null,null,3]],null,\"pl\"]");
        assertEquals("pl", result.source());
        assertTrue(result.sameLanguage("pl"));
        assertFalse(result.sameLanguage("en"));
        assertTrue(new TranslationService.Translation("x", "zh-CN").sameLanguage("zh-TW"),
                "regions aside, it is the same language");
        assertNull(TranslationService.parse("[[[\"hello\",\"siema\",null,null,3]]]").source());
    }

    @Test
    void namesLanguagesInTheirOwnLanguage() {
        assertEquals("Polski", TranslationService.languageName("pl"));
        assertEquals("Deutsch", TranslationService.languageName("de"));
        // Not offered in the pickers, but the detector can still answer with it.
        assertEquals("Hrvatski", TranslationService.languageName("hr"));
        assertEquals("?", TranslationService.languageName(null));
    }

    @Test
    void rejectsAnUnexpectedShape() {
        assertThrows(ApiException.class, () -> TranslationService.parse("{\"error\":\"nope\"}"));
        assertThrows(ApiException.class, () -> TranslationService.parse("not json at all"));
    }
}
