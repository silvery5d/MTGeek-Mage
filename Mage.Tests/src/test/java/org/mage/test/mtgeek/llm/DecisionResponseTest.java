package org.mage.test.mtgeek.llm;

import org.junit.Test;
import static org.junit.Assert.*;

public class DecisionResponseTest {
    @Test
    public void fromJson_basic() {
        DecisionResponse r = DecisionResponse.fromJson("{\"choices\":[1],\"rationale\":\"打\"}");
        assertArrayEquals(new int[]{1}, r.choices);
        assertEquals("打", r.rationale);
    }

    @Test
    public void fromJson_multipleChoices() {
        DecisionResponse r = DecisionResponse.fromJson("{\"choices\":[0,2,3],\"rationale\":\"r\"}");
        assertArrayEquals(new int[]{0,2,3}, r.choices);
    }

    @Test
    public void fromJson_negativeForSelectBlockers() {
        DecisionResponse r = DecisionResponse.fromJson("{\"choices\":[-1,0],\"rationale\":\"r\"}");
        assertArrayEquals(new int[]{-1,0}, r.choices);
    }

    @Test(expected = IllegalArgumentException.class)
    public void fromJson_rejectsMissingChoices() {
        DecisionResponse.fromJson("{\"rationale\":\"r\"}");
    }

    @Test(expected = IllegalArgumentException.class)
    public void fromJson_rejectsMissingRationale() {
        DecisionResponse.fromJson("{\"choices\":[0]}");
    }
}
