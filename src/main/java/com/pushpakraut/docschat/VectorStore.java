package com.pushpakraut.docschat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps chunk embeddings in memory, separately for each visitor, and finds the closest ones by cosine similarity.
 * Memory is bounded: a visitor's space exists only after an upload, idle visitors are dropped,
 * and when the server is full new uploads are refused rather than evicting anyone's documents.
 * shortcut: in-memory and lost on restart; for real clients use PostgreSQL + pgvector with a user id column.
 */
@Component
public class VectorStore {
    public record Chunk(String file, int page, String text, float[] vector) {}
    public record Match(Chunk chunk, double score) {}

    private static final class Space {
        final List<Chunk> chunks = new ArrayList<>();
        Instant lastUsed = Instant.now();
    }

    private final Map<String, Space> spaces = new ConcurrentHashMap<>();
    private final int maxVisitors;
    private final int maxTotalChunks;
    private final Duration idle;
    private int totalChunks;

    public VectorStore(@Value("${app.limits.max-visitors:200}") int maxVisitors,
                       @Value("${app.limits.max-total-chunks:15000}") int maxTotalChunks,
                       @Value("${app.limits.idle-minutes:30}") long idleMinutes) {
        this.maxVisitors = maxVisitors;
        this.maxTotalChunks = maxTotalChunks;
        this.idle = Duration.ofMinutes(idleMinutes);
    }

    /** The only call that creates a visitor's space. Refuses (503) instead of evicting other visitors. */
    public synchronized void replaceFile(String visitor, String file, List<Chunk> newChunks) {
        evictIdle();
        Space s = spaces.get(visitor);
        int freed = s == null ? 0 : (int) s.chunks.stream().filter(c -> c.file().equals(file)).count();
        if (s == null && spaces.size() >= maxVisitors || totalChunks - freed + newChunks.size() > maxTotalChunks) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The demo is busy right now. Please try again later.");
        }
        if (s == null) spaces.put(visitor, s = new Space());
        s.chunks.removeIf(c -> c.file().equals(file));
        s.chunks.addAll(newChunks);
        s.lastUsed = Instant.now();
        totalChunks += newChunks.size() - freed;
    }

    public synchronized void removeFile(String visitor, String file) {
        Space s = spaces.get(visitor);
        if (s == null) return;
        int before = s.chunks.size();
        s.chunks.removeIf(c -> c.file().equals(file));
        totalChunks -= before - s.chunks.size();
        if (s.chunks.isEmpty()) spaces.remove(visitor);
    }

    public synchronized List<String> files(String visitor) {
        Space s = touch(visitor);
        return s == null ? List.of() : s.chunks.stream().map(Chunk::file).distinct().sorted().toList();
    }

    public synchronized List<Match> search(String visitor, float[] query, int k) {
        Space s = touch(visitor);
        if (s == null) return List.of();
        return s.chunks.stream()
                .map(c -> new Match(c, cosine(query, c.vector())))
                .sorted(Comparator.comparingDouble(Match::score).reversed())
                .limit(k)
                .toList();
    }

    synchronized int totalChunks() { return totalChunks; }

    private Space touch(String visitor) {
        Space s = spaces.get(visitor);  // reads never create a space
        if (s != null) s.lastUsed = Instant.now();
        return s;
    }

    private void evictIdle() {
        Instant cutoff = Instant.now().minus(idle);
        spaces.values().removeIf(s -> {
            boolean old = s.lastUsed.isBefore(cutoff);
            if (old) totalChunks -= s.chunks.size();
            return old;
        });
    }

    static double cosine(float[] a, float[] b) {
        if (a.length != b.length) return 0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return (na == 0 || nb == 0) ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
