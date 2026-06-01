package org.mage.test.mtgeek.scripts;

import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;
import mage.cards.repository.CardScanner;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * One-shot generator: reads decks/source/*.txt and writes .dck files to
 * Mage.Tests/src/test/resources/mtgeek/.
 *
 * Run once (from the MTGeek-Mage root or Mage.Tests module) with:
 *   mvn test -pl Mage.Tests -Dtest=BuildDck -DfailIfNoTests=false
 *
 * NOT intended for CI. The generated .dck files are the deliverable.
 * BuildDck itself is committed as a reusable tool for future deck additions.
 */
public class BuildDck {

    @BeforeClass
    public static void scanCards() {
        // Populate CardRepository from the XMage card class-path.
        // CardScanner.scan() is idempotent (guarded by CardScanner.scanned flag).
        if (!CardScanner.scanned) {
            List<String> errors = new ArrayList<>();
            CardScanner.scan(errors);
            if (!errors.isEmpty()) {
                Assert.fail("CardScanner errors:\n" + String.join("\n", errors));
            }
        }
        System.out.println("[BuildDck] Card database ready. Sample check: Brainstorm="
                + (CardRepository.instance.findCard("Brainstorm") != null ? "FOUND" : "MISSING"));
    }

    @Test
    public void generateDckFiles() throws IOException {
        // Maven surefire runs from the module directory (Mage.Tests/).
        // Source decks live in <repo-root>/decks/source/
        Path sourcesDir = Paths.get("..","decks","source").toAbsolutePath().normalize();
        Path outputDir  = Paths.get("src","test","resources","mtgeek").toAbsolutePath().normalize();
        Files.createDirectories(outputDir);

        System.out.println("[BuildDck] Source dir : " + sourcesDir);
        System.out.println("[BuildDck] Output dir : " + outputDir);

        String[] deckFiles = {"show-and-tell.txt", "dimir-tempo.txt"};
        for (String deckFile : deckFiles) {
            String outName = deckFile.replace(".txt", ".dck");
            Path inPath  = sourcesDir.resolve(deckFile);
            Path outPath = outputDir.resolve(outName);

            System.out.println("[BuildDck] Converting " + deckFile + " ...");
            List<String> missing = convertDeck(inPath, outPath);
            System.out.println("[BuildDck]   -> " + outPath);
            if (!missing.isEmpty()) {
                System.out.println("[BuildDck]   MISSING (" + missing.size() + "): " + missing);
            } else {
                System.out.println("[BuildDck]   All cards resolved.");
            }
        }
    }

    /**
     * Converts a plain-text deck list to XMage .dck format.
     * Source format per line: {@code <qty> <Card Name>}
     * Output format per line: {@code <qty> [<SET>:<NUMBER>] <Card Name>}
     * Lines for cards not found in XMage: {@code # MISSING IN XMAGE: <qty> <Card Name>}
     *
     * @return list of card names that were not found in XMage
     */
    private List<String> convertDeck(Path inPath, Path outPath) throws IOException {
        List<String> sourceLines = Files.readAllLines(inPath, StandardCharsets.UTF_8);
        List<String> missing = new ArrayList<>();

        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(outPath, StandardCharsets.UTF_8))) {
            for (String rawLine : sourceLines) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue; // skip blanks and comments in source
                }

                // Expected source format: "<qty> <Card Name>"
                int spaceIdx = line.indexOf(' ');
                if (spaceIdx < 0) {
                    System.err.println("[BuildDck] Skipping malformed line: " + line);
                    continue;
                }
                String qtyStr   = line.substring(0, spaceIdx).trim();
                String cardName = line.substring(spaceIdx + 1).trim();

                int qty;
                try {
                    qty = Integer.parseInt(qtyStr);
                } catch (NumberFormatException e) {
                    System.err.println("[BuildDck] Skipping non-numeric qty: " + line);
                    continue;
                }

                // findOldestNonPromoVersionCard gives a deterministic, stable printing
                // (oldest non-promo set the card appears in, ties broken by card number).
                CardInfo info = CardRepository.instance.findOldestNonPromoVersionCard(cardName);

                if (info == null) {
                    out.println(String.format("# MISSING IN XMAGE: %d %s", qty, cardName));
                    missing.add(cardName);
                } else {
                    out.println(String.format("%d [%s:%s] %s",
                            qty, info.getSetCode(), info.getCardNumber(), info.getName()));
                }
            }
        }

        return missing;
    }
}
