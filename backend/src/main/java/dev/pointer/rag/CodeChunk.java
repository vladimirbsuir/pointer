package dev.pointer.rag;

record CodeChunk(String path, String symbol, String kind, int startLine, int endLine, String content) {

    String embeddingText() {
        var header = symbol == null || symbol.isBlank() ? path : path + " · " + symbol;
        return header + "\n\n" + content;
    }
}
