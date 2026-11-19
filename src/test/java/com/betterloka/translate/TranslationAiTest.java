package com.betterloka.translate;

import com.betterloka.assistant.AssistantClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Translator on the server's AI: lines gathered into one request, Google whenever it cannot. */
class TranslationAiTest {
    /** The server's AI, as the mod reaches it: answers "[target] text", or fails when told to. */
    static final class FakeAi implements TranslationService.AiTranslator {
        final List<List<String>> asked = new ArrayList<>();
        volatile String failWith;
        volatile int leaveOut = -1;

        @Override
        public synchronized CompletableFuture<List<AssistantClient.Translated>> translate(List<String> texts,
                                                                                         List<String> sources, String target) {
            asked.add(List.copyOf(texts));
            if (failWith != null) {
                return CompletableFuture.failedFuture(new AssistantClient.Failure(failWith, "nope"));
            }
            List<AssistantClient.Translated> out = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                out.add(i == leaveOut ? null : new AssistantClient.Translated("[" + target + "] " + texts.get(i), "en"));
            }
            return CompletableFuture.completedFuture(out);
        }
    }

    private final AtomicInteger googled = new AtomicInteger();
    private final TranslationService.Machine google = (text, source, target) -> {
        googled.incrementAndGet();
        return new TranslationService.Translation("(google) " + text, "en");
    };

    @Test
    void linesAskedTogetherGoInOneRequestAndComeBackFromTheAi() throws Exception {
        FakeAi ai = new FakeAi();
        TranslationService service = new TranslationService(null, () -> ai, () -> true, google);
        List<CompletableFuture<TranslationService.Translation>> lines = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            lines.add(service.translate("line " + i, "auto", "pl"));
        }
        lines.add(service.translate("line 0", "auto", "pl"));
        for (int i = 0; i < 5; i++) {
            TranslationService.Translation translation = lines.get(i).get(5, TimeUnit.SECONDS);
            assertEquals("[pl] line " + i, translation.text());
            assertTrue(translation.byAi());
            assertEquals("en", translation.source());
        }
        assertEquals("[pl] line 0", lines.get(5).get(5, TimeUnit.SECONDS).text(), "the same line twice: asked once");
        assertEquals(1, ai.asked.size(), "five lines, one request");
        assertEquals(5, ai.asked.get(0).size());
        assertEquals(0, googled.get());

        // Asked again: from the mod's own memory, nothing sent.
        assertEquals("[pl] line 3", service.translate("line 3", "auto", "pl").get(5, TimeUnit.SECONDS).text());
        assertEquals(1, ai.asked.size());
    }

    @Test
    void moreThanABatchIsSplitAndEachTargetGoesOnItsOwn() throws Exception {
        FakeAi ai = new FakeAi();
        TranslationService service = new TranslationService(null, () -> ai, () -> true, google);
        List<CompletableFuture<TranslationService.Translation>> lines = new ArrayList<>();
        for (int i = 0; i < TranslationService.BATCH + 5; i++) {
            lines.add(service.translate("chat " + i, "auto", "pl"));
        }
        lines.add(service.translate("hello", "auto", "de"));
        for (CompletableFuture<TranslationService.Translation> line : lines) {
            assertTrue(line.get(5, TimeUnit.SECONDS).byAi());
        }
        assertEquals("[de] hello", lines.get(lines.size() - 1).get().text());
        int sent = ai.asked.stream().mapToInt(List::size).sum();
        assertEquals(TranslationService.BATCH + 6, sent);
        assertTrue(ai.asked.stream().allMatch(batch -> batch.size() <= TranslationService.BATCH));
    }

    @Test
    void whenTheServerCannotGoogleDoesAndTheServerIsLeftAlone() throws Exception {
        FakeAi ai = new FakeAi();
        ai.failWith = "budget_day";
        TranslationService service = new TranslationService(null, () -> ai, () -> true, google);
        TranslationService.Translation first = service.translate("who has pots", "auto", "pl").get(5, TimeUnit.SECONDS);
        assertEquals("(google) who has pots", first.text());
        assertFalse(first.byAi());
        assertEquals(1, ai.asked.size());

        // Paused: the next lines go straight to Google, the server is not asked.
        assertEquals("(google) gg", service.translate("gg", "auto", "pl").get(5, TimeUnit.SECONDS).text());
        assertEquals(1, ai.asked.size());
        assertEquals(2, googled.get());
    }

    @Test
    void aLineTheServerLeftOutIsTranslatedByGoogleAndTheRestByTheAi() throws Exception {
        FakeAi ai = new FakeAi();
        ai.leaveOut = 1;
        TranslationService service = new TranslationService(null, () -> ai, () -> true, google);
        CompletableFuture<TranslationService.Translation> a = service.translate("a", "auto", "pl");
        CompletableFuture<TranslationService.Translation> b = service.translate("b", "auto", "pl");
        assertEquals("[pl] a", a.get(5, TimeUnit.SECONDS).text());
        assertEquals("(google) b", b.get(5, TimeUnit.SECONDS).text());
    }

    @Test
    void withTheAiTurnedOffEveryLineGoesToGoogle() throws Exception {
        FakeAi ai = new FakeAi();
        AtomicBoolean on = new AtomicBoolean(false);
        TranslationService service = new TranslationService(null, () -> ai, on::get, google);
        assertEquals("(google) hi", service.translate("hi", "auto", "pl").get(5, TimeUnit.SECONDS).text());
        assertEquals(0, ai.asked.size());
        on.set(true);
        assertTrue(service.translate("hej", "auto", "en").get(5, TimeUnit.SECONDS).byAi());
    }
}
