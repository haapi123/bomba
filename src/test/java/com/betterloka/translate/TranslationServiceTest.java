package com.betterloka.translate;

import com.betterloka.api.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The translation endpoint answers with a nested array rather than an object; this pins its shape. */
class TranslationServiceTest {

    @Test
    void joinsEverySentenceOfTheResponse() throws Exception {
        String body = "[[[\"ktos chetny na walke? \",\"anyone up for a fight? \",null,null,3],"
                + "[\"potrzebujemy jeszcze 3\",\"we need 3 more\",null,null,3]],null,\"en\"]";
        assertEquals("ktos chetny na walke? potrzebujemy jeszcze 3", TranslationService.parse(body));
    }

    @Test
    void toleratesASingleSentence() throws Exception {
        assertEquals("siema", TranslationService.parse("[[[\"siema\",\"hello\",null,null,3]],null,\"en\"]"));
    }

    @Test
    void rejectsAnUnexpectedShape() {
        assertThrows(ApiException.class, () -> TranslationService.parse("{\"error\":\"nope\"}"));
        assertThrows(ApiException.class, () -> TranslationService.parse("not json at all"));
    }
}
