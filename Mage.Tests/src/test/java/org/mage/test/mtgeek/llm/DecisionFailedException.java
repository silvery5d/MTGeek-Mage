package org.mage.test.mtgeek.llm;

public class DecisionFailedException extends RuntimeException {
    public DecisionFailedException(String message, Throwable cause) { super(message, cause); }
    public DecisionFailedException(String message) { super(message); }
}
