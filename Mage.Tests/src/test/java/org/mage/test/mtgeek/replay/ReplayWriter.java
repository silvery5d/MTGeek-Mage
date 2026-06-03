package org.mage.test.mtgeek.replay;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ReplayWriter {

    public static final class Metadata {
        public String matchId;
        public String timestamp;
        public String deckA, deckB;
        public String playerA, playerB;
        public String winner;     // "A" / "B" / "draw"
        public int endTurn;
        public String endReason;
    }

    public static void write(Path out, Metadata meta, List<ReplayEvent> events) throws IOException {
        Files.createDirectories(out.getParent());
        StringBuilder sb = new StringBuilder(8192);
        sb.append("{\n");
        sb.append("  \"version\":1,\n");
        sb.append("  \"matchId\":").append(ReplayEvent.jsonStr(meta.matchId)).append(",\n");
        sb.append("  \"timestamp\":").append(ReplayEvent.jsonStr(meta.timestamp)).append(",\n");
        sb.append("  \"decks\":{\"A\":").append(ReplayEvent.jsonStr(meta.deckA))
          .append(",\"B\":").append(ReplayEvent.jsonStr(meta.deckB)).append("},\n");
        sb.append("  \"players\":{\"A\":").append(ReplayEvent.jsonStr(meta.playerA))
          .append(",\"B\":").append(ReplayEvent.jsonStr(meta.playerB)).append("},\n");
        sb.append("  \"result\":{\"winner\":").append(ReplayEvent.jsonStr(meta.winner))
          .append(",\"endTurn\":").append(meta.endTurn)
          .append(",\"reason\":").append(ReplayEvent.jsonStr(meta.endReason)).append("},\n");
        sb.append("  \"events\":[\n");
        for (int i = 0; i < events.size(); i++) {
            if (i > 0) sb.append(",\n");
            sb.append("    ").append(events.get(i).toJson());
        }
        if (!events.isEmpty()) sb.append("\n  ]\n");
        else sb.append("  ]\n");
        sb.append("}\n");
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    }

    private ReplayWriter() {}
}
