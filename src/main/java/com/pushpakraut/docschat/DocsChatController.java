package com.pushpakraut.docschat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class DocsChatController {
    public record UploadResponse(String file, int pages, int chunks) {}
    public record Question(@NotBlank @Size(max = 1000) String question) {}
    public record Source(String file, int page, String excerpt, double score) {}
    public record Answer(String answer, List<Source> sources) {}

    static final String SYSTEM_PROMPT = """
            You answer questions using ONLY the context excerpts provided.
            If the answer is not in the context, say "I couldn't find that in the uploaded documents."
            Be concise and answer directly. Do not mention file names, page numbers or "the context".
            """;

    private final AiClient ai;
    private final VectorStore store;
    private final int chunkSize, overlap, topK, maxFiles, maxPages;

    public DocsChatController(AiClient ai, VectorStore store,
                              @Value("${app.rag.chunk-size}") int chunkSize,
                              @Value("${app.rag.chunk-overlap}") int overlap,
                              @Value("${app.rag.top-k}") int topK,
                              @Value("${app.limits.max-files-per-visitor}") int maxFiles,
                              @Value("${app.limits.max-pages}") int maxPages) {
        this.ai = ai;
        this.store = store;
        this.chunkSize = chunkSize;
        this.overlap = overlap;
        this.topK = topK;
        this.maxFiles = maxFiles;
        this.maxPages = maxPages;
    }

    /** Step 1-2: extract text per page, split into chunks, embed, store for this visitor only. */
    @PostMapping("/documents")
    public UploadResponse upload(@RequestAttribute(RequestGuard.VISITOR) String visitor,
                                 @RequestParam("file") MultipartFile file) throws IOException {
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase().endsWith(".pdf")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Please upload a PDF file");
        }
        name = name.replaceAll("[^\\w .()-]", "_");
        List<String> existing = store.files(visitor);
        if (!existing.contains(name) && existing.size() >= maxFiles) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Limit is " + maxFiles + " PDFs. Remove one first.");
        }
        List<VectorStore.Chunk> chunks = new ArrayList<>();
        int pages;
        try (PDDocument pdf = Loader.loadPDF(file.getBytes())) {
            pages = pdf.getNumberOfPages();
            if (pages > maxPages) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Limit is " + maxPages + " pages per PDF.");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            for (int p = 1; p <= pages; p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                List<String> texts = Chunker.split(stripper.getText(pdf), chunkSize, overlap).stream()
                        .filter(t -> !t.isBlank()).toList();
                if (texts.isEmpty()) continue;
                List<float[]> vectors = ai.embed(texts);
                for (int i = 0; i < texts.size(); i++) {
                    chunks.add(new VectorStore.Chunk(name, p, texts.get(i), vectors.get(i)));
                }
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read that PDF.");
        }
        if (chunks.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No text found. Scanned PDFs need OCR first.");
        }
        store.replaceFile(visitor, name, chunks); // re-uploading a file replaces it
        return new UploadResponse(name, pages, chunks.size());
    }

    @GetMapping("/documents")
    public List<String> documents(@RequestAttribute(RequestGuard.VISITOR) String visitor) {
        return store.files(visitor);
    }

    @DeleteMapping("/documents/{file}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestAttribute(RequestGuard.VISITOR) String visitor, @PathVariable String file) {
        store.removeFile(visitor, file);
    }

    /** Step 3-4: embed the question, find the closest chunks, ask the model to answer from them only. */
    @PostMapping("/chat")
    public Answer chat(@RequestAttribute(RequestGuard.VISITOR) String visitor, @Valid @RequestBody Question q) {
        if (store.files(visitor).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload a PDF first");
        }
        float[] qVector = ai.embed(List.of(q.question())).getFirst();
        List<VectorStore.Match> matches = store.search(visitor, qVector, topK);

        String context = matches.stream().map(m -> m.chunk().text()).collect(Collectors.joining("\n\n---\n\n"));
        String answer = ai.chat(SYSTEM_PROMPT, "Context:\n" + context + "\n\nQuestion: " + q.question());

        List<Source> sources = matches.stream().map(m -> new Source(m.chunk().file(), m.chunk().page(),
                m.chunk().text().length() > 220 ? m.chunk().text().substring(0, 220) + "…" : m.chunk().text(),
                Math.round(m.score() * 100) / 100.0)).toList();
        return new Answer(answer, sources);
    }
}
