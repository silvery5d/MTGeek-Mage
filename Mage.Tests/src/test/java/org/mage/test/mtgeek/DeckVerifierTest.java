package org.mage.test.mtgeek;

import org.junit.Test;
import mage.player.ai.mtgeek.DeckVerifier;
import mage.player.ai.mtgeek.VerifyReport;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

public class DeckVerifierTest {

    @Test
    public void showAndTellDeckAllCardsPresent() {
        VerifyReport r = DeckVerifier.verify(
            "src/test/resources/mtgeek/show-and-tell.dck"
        );
        assertTrue("Missing cards: " + r.missing, r.missing.isEmpty());
        assertTrue("Ambiguous cards: " + r.ambiguous, r.ambiguous.isEmpty());
        assertEquals(60, r.totalCount);
    }

    @Test
    public void dimirTempoDeckAllCardsPresent() {
        VerifyReport r = DeckVerifier.verify(
            "src/test/resources/mtgeek/dimir-tempo.dck"
        );
        assertTrue("Missing cards: " + r.missing, r.missing.isEmpty());
        assertTrue("Ambiguous cards: " + r.ambiguous, r.ambiguous.isEmpty());
        assertEquals(60, r.totalCount);
    }
}
