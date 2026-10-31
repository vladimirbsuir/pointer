package dev.pointer.provider.llm;

public interface StreamListener {

    default void onText(String delta) {}

    default void onReasoning(String delta) {}

    default void onToolCallDelta(int index, String id, String name, String argumentsDelta) {}

    default void onUsage(Usage usage) {}

    record Usage(long promptTokens, long completionTokens) {}
}
