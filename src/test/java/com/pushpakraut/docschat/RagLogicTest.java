package com.pushpakraut.docschat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests the parts that don't need a running model: chunking and vector search. */
class RagLogicTest {

    @Test
    void chunksCoverWholeTextWithOverlap() {
        String text = "Sentence number one. ".repeat(100);
        List<String> chunks = Chunker.split(text, 200, 50);
        assertTrue(chunks.size() > 5);
        chunks.forEach(c -> assertTrue(c.length() <= 200, "chunk too long: " + c.length()));
        assertTrue(chunks.getLast().endsWith("one."));
    }

    @Test
    void shortTextIsOneChunk() {
        assertEquals(List.of("Hello world."), Chunker.split("  Hello\n\n world. ", 800, 150));
    }

    @Test
    void searchReturnsClosestChunkFirst() {
        VectorStore store = new VectorStore(10, 1000, 60);
        store.replaceFile("v1", "a.pdf", List.of(
                new VectorStore.Chunk("a.pdf", 1, "refunds", new float[]{1, 0, 0}),
                new VectorStore.Chunk("a.pdf", 2, "shipping", new float[]{0, 1, 0})));
        store.replaceFile("v1", "b.pdf", List.of(new VectorStore.Chunk("b.pdf", 1, "returns", new float[]{0.9f, 0.1f, 0})));
        var top = store.search("v1", new float[]{1, 0, 0}, 2);
        assertEquals("refunds", top.get(0).chunk().text());
        assertEquals("returns", top.get(1).chunk().text());
        assertEquals(1.0, top.get(0).score(), 1e-6);
    }

    @Test
    void removeFileEmptiesStore() {
        VectorStore store = new VectorStore(10, 1000, 60);
        store.replaceFile("v1", "a.pdf", List.of(new VectorStore.Chunk("a.pdf", 1, "x", new float[]{1})));
        store.removeFile("v1", "a.pdf");
        assertTrue(store.files("v1").isEmpty());
    }

    @Test
    void visitorsNeverSeeEachOthersFiles() {
        VectorStore store = new VectorStore(10, 1000, 60);
        store.replaceFile("alice", "secret.pdf", List.of(new VectorStore.Chunk("secret.pdf", 1, "salary", new float[]{1})));
        assertTrue(store.files("bob").isEmpty());
        assertTrue(store.search("bob", new float[]{1}, 4).isEmpty());
        assertEquals(List.of("secret.pdf"), store.files("alice"));
    }

    @Test
    void readsDoNotCreateVisitorsOrEvictAnyone() {
        VectorStore store = new VectorStore(2, 1000, 60);
        store.replaceFile("victim", "a.pdf", List.of(new VectorStore.Chunk("a.pdf", 1, "x", new float[]{1})));
        for (int i = 0; i < 500; i++) store.files("anon-" + i);
        assertEquals(List.of("a.pdf"), store.files("victim"));
    }

    @Test
    void fullServerRefusesNewUploadsInsteadOfEvicting() {
        VectorStore store = new VectorStore(1, 1000, 60);
        store.replaceFile("a", "a.pdf", List.of(new VectorStore.Chunk("a.pdf", 1, "x", new float[]{1})));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () ->
                store.replaceFile("b", "b.pdf", List.of(new VectorStore.Chunk("b.pdf", 1, "y", new float[]{1}))));
        assertEquals(List.of("a.pdf"), store.files("a"));
    }

    @Test
    void totalChunkCapIsEnforcedAndFreedOnDelete() {
        VectorStore store = new VectorStore(10, 3, 60);
        var two = List.of(new VectorStore.Chunk("a.pdf", 1, "x", new float[]{1}), new VectorStore.Chunk("a.pdf", 2, "y", new float[]{1}));
        store.replaceFile("a", "a.pdf", two);
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> store.replaceFile("b", "b.pdf", two));
        store.replaceFile("a", "a.pdf", two); // re-upload of same file reuses its space
        store.removeFile("a", "a.pdf");
        assertEquals(0, store.totalChunks());
        store.replaceFile("b", "b.pdf", two);
    }
}
