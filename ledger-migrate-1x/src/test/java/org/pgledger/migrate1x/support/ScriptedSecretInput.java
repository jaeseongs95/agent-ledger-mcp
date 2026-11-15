package org.pgledger.migrate1x.support;

import org.pgledger.migrate1x.secret.SecretInput;

/** Test double for the input boundary; the product implementation is Console.readPassword only. */
public final class ScriptedSecretInput implements SecretInput {

    private final char[] answer;
    private int reads;
    private String lastPrompt;

    public ScriptedSecretInput(char[] answer) {
        this.answer = answer;
    }

    @Override
    public char[] read(String prompt) {
        reads++;
        lastPrompt = prompt;
        return answer;
    }

    public int reads() {
        return reads;
    }

    public String lastPrompt() {
        return lastPrompt;
    }
}
