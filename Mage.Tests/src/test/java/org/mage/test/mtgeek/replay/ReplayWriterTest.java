package org.mage.test.mtgeek.replay;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.Assert.*;

public class ReplayWriterTest {

    @Test
    public void writes_validJsonWithMetadataAndEvents() throws IOException {
        ReplayWriter.Metadata meta = new ReplayWriter.Metadata();
        meta.matchId = "test-match-1";
        meta.timestamp = "2026-06-04T00:00:00Z";
        meta.deckA = "a.dck"; meta.deckB = "b.dck";
        meta.playerA = "MTGeekSimplePlayer"; meta.playerB = "MTGeekSimplePlayer";
        meta.winner = "A"; meta.endTurn = 5; meta.endReason = "test";

        List<ReplayEvent> events = new ArrayList<>();
        ReplayEvent e1 = new ReplayEvent(0, 1, "turn_start");
        e1.actor = "A";
        events.add(e1);
        ReplayEvent e2 = new ReplayEvent(1, 1, "life_change");
        e2.actor = "B";
        e2.payload.put("delta", -3);
        e2.payload.put("newTotal", 17);
        events.add(e2);

        Path tmp = Files.createTempFile("replay-test", ".json");
        ReplayWriter.write(tmp, meta, events);

        String content = Files.readString(tmp);
        assertTrue("contains version 1", content.contains("\"version\":1"));
        assertTrue("contains matchId",   content.contains("\"matchId\":\"test-match-1\""));
        assertTrue("contains winner",    content.contains("\"winner\":\"A\""));
        assertTrue("contains endTurn",   content.contains("\"endTurn\":5"));
        assertTrue("contains events array", content.contains("\"events\":["));
        assertTrue("contains turn_start", content.contains("\"type\":\"turn_start\""));
        assertTrue("contains life_change",content.contains("\"type\":\"life_change\""));
        assertTrue("contains delta -3",  content.contains("\"delta\":-3"));
        Files.deleteIfExists(tmp);
    }

    @Test
    public void writes_emptyEventsList() throws IOException {
        ReplayWriter.Metadata meta = new ReplayWriter.Metadata();
        meta.matchId = "empty";
        meta.timestamp = "now";
        meta.deckA = "a"; meta.deckB = "b";
        meta.playerA = "x"; meta.playerB = "y";
        meta.winner = "draw"; meta.endTurn = 0; meta.endReason = "no events";

        Path tmp = Files.createTempFile("replay-empty", ".json");
        ReplayWriter.write(tmp, meta, new ArrayList<>());
        String content = Files.readString(tmp);
        assertTrue("empty array still produces valid JSON",
            content.contains("\"events\":[") && content.contains("]"));
        Files.deleteIfExists(tmp);
    }
}
