package mage.player.ai.mtgeek;

import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DeckVerifier {

    // .dck line format (XMage standard): [SB:] <qty> [<SET>:<NUM>] <name>
    // Skip: blank lines, lines starting with '#' (comments), and 'NAME:'.
    // SB: lines (sideboard) are accepted but ignored for B0'.
    // Note: '//' is NOT a comment marker — split-card names contain it.
    private static final Pattern LINE = Pattern.compile(
        "^(SB:)?\\s*(\\d+)\\s*\\[([^\\]:]+):([^\\]]+)\\]\\s+(.+?)\\s*$"
    );

    public static VerifyReport verify(String dckPath) {
        VerifyReport report = new VerifyReport();
        List<String> lines;
        try {
            lines = Files.readAllLines(Path.of(dckPath), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Cannot read .dck file: " + dckPath, e);
        }
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#")) continue;       // comment
            if (line.startsWith("NAME:")) continue;   // deck name header
            if (line.startsWith("AUTHOR:")) continue; // deck author header
            if (line.startsWith("LAYOUT")) continue;  // covers LAYOUT and LAYOUT MAIN/SIDEBOARD variants
            Matcher m = LINE.matcher(line);
            if (!m.matches()) {
                report.missing.add("UNPARSED: " + line);
                continue;
            }
            boolean isSideboard = m.group(1) != null;
            if (isSideboard) continue; // B0' ignores sideboard
            int qty = Integer.parseInt(m.group(2));
            String setCode = m.group(3);
            String cardNum = m.group(4);
            String cardName = m.group(5);
            report.totalCount += qty;

            List<CardInfo> matches = CardRepository.instance.findCards(cardName);
            if (matches == null || matches.isEmpty()) {
                report.missing.add(cardName);
            } else if (matches.stream()
                            .noneMatch(c -> setCode.equalsIgnoreCase(c.getSetCode())
                                       && cardNum.equalsIgnoreCase(c.getCardNumber()))) {
                report.ambiguous.add(String.format(
                    "%s [%s:%s] not found in that set/number; available: %s",
                    cardName, setCode, cardNum,
                    matches.stream().map(c -> c.getSetCode() + ":" + c.getCardNumber())
                                    .limit(5).toList()
                ));
            } else {
                report.ok.add(cardName + " [" + setCode + ":" + cardNum + "]");
            }
        }
        return report;
    }
}
