package com.jfx.git.ui.syntax;

import com.jfx.git.ui.FxHarness;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Only the newest request per surface is worked out.
 *
 * <p>Every pause in typing asks for the whole document to be coloured again; queued one
 * behind another, each was computed in full and then thrown away as stale.
 */
class HighlightLaneTest {

    @BeforeAll
    static void startToolkit() {
        FxHarness.start();
    }

    @AfterEach
    void backToInline() {
        HighlightLane.setSynchronousForTest(true);
    }

    @Test
    void aRequestSupersededBeforeItsTurnIsNeverComputed() throws Exception {
        HighlightLane.setSynchronousForTest(false);
        CountDownLatch release = new CountDownLatch(1);
        HighlightLane.submit(() -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }, ignored -> { });

        Object surface = new Object();
        List<Integer> computed = new CopyOnWriteArrayList<>();
        List<Integer> delivered = new CopyOnWriteArrayList<>();
        for (int i = 1; i <= 3; i++) {
            int request = i;
            HighlightLane.submit(surface, () -> {
                computed.add(request);
                return request;
            }, delivered::add);
        }
        release.countDown();
        HighlightLane.awaitIdleForTest();
        FxHarness.run(() -> { });

        assertEquals(List.of(3), computed, "only the newest request is worth computing");
        assertEquals(List.of(3), delivered);
    }
}
