package com.pushpakraut.docschat;

import java.util.ArrayList;
import java.util.List;

/** Splits text into overlapping chunks, preferring to break at sentence or word boundaries. */
public final class Chunker {
    private Chunker() {}

    public static List<String> split(String text, int size, int overlap) {
        if (overlap >= size) throw new IllegalArgumentException("overlap must be smaller than size");
        String clean = text.replaceAll("\\s+", " ").trim();
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < clean.length()) {
            int end = Math.min(start + size, clean.length());
            if (end < clean.length()) {
                int cut = Math.max(clean.lastIndexOf(". ", end), clean.lastIndexOf(' ', end));
                if (cut > start + size / 2) end = cut + 1;
            }
            chunks.add(clean.substring(start, end).trim());
            if (end == clean.length()) break;
            start = Math.max(end - overlap, start + 1);
        }
        return chunks;
    }
}
