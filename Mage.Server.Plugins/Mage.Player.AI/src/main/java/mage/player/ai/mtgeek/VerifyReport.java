package mage.player.ai.mtgeek;

import java.util.ArrayList;
import java.util.List;

public class VerifyReport {
    public final List<String> ok = new ArrayList<>();
    public final List<String> missing = new ArrayList<>();
    public final List<String> ambiguous = new ArrayList<>();
    public int totalCount = 0;

    @Override
    public String toString() {
        return String.format("VerifyReport{total=%d, ok=%d, missing=%s, ambiguous=%s}",
            totalCount, ok.size(), missing, ambiguous);
    }
}
