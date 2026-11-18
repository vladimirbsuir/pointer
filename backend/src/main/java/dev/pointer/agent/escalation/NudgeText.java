package dev.pointer.agent.escalation;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

final class NudgeText {

    private static final Map<String, String> DESCRIPTIONS = Map.of(
            StruggleSignals.REPEAT_CALL, "you called the same tool with the same arguments again",
            StruggleSignals.ERROR_STREAK, "several tool calls in a row failed",
            StruggleSignals.EDIT_THRASH, "you changed the same file over and over",
            StruggleSignals.MALFORMED_CALL, "some tool calls were invalid or named tools you do not have",
            StruggleSignals.DENIED, "the user denied an action",
            StruggleSignals.READ_ONLY_STREAK, "many steps went by without any change",
            StruggleSignals.CHECK_FAILED, "the check of your previous changes failed");

    private NudgeText() {}

    static String of(List<String> signals) {
        var reasons = signals.stream()
                .map(signal -> DESCRIPTIONS.getOrDefault(signal, signal))
                .collect(Collectors.joining("; "));
        return "Note from Pointer about your last steps: " + reasons + ". Do not repeat what did not work. "
                + "Re-read the error messages, check your assumptions and change your approach.";
    }
}
