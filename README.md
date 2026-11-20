# Local AI Docs Chatbot (RAG)

Upload PDFs and ask questions about them in plain English. Answers come **only from your documents**, with the source pages one click away. Runs on a **local, free AI model via [Ollama](https://ollama.com)**: no API key, no per-question cost, and documents never leave the machine.

**Stack:** Java 21 · Spring Boot 4.1 · any OpenAI-compatible model: Ollama locally (`llama3.2:3b` + `nomic-embed-text`) or Google Gemini when hosted · Apache PDFBox · vanilla HTML/JS UI (terminal-style, Martian Mono)

## How it works (Retrieval-Augmented Generation)
1. **Extract:** PDFBox reads the text of each page.
2. **Chunk:** text is split into ~800-character overlapping chunks so no fact is cut in half.
3. **Embed:** each chunk is turned into a vector (a list of numbers that captures its meaning) by `nomic-embed-text`.
4. **Retrieve:** a question is embedded the same way; the 4 closest chunks are found with cosine similarity.
5. **Answer:** the question plus only those chunks go to the chat model, with instructions to answer from the context or say it can't find the answer.

```
PDF ─► text per page ─► chunks ─► embeddings ─► vector store
                                                     │
question ─► embedding ─► top-4 similar chunks ───────┘
                              │
                              ▼
                 LLM (answer from context only) ─► answer + sources
```

## Run locally
```bash
brew install ollama            # or download from ollama.com
ollama serve &
ollama pull nomic-embed-text
ollama pull llama3.2:3b
./mvnw spring-boot:run
```
Open http://localhost:8081, upload a PDF (try `sample-docs/acme-handbook.pdf`), and ask a question.

Runs comfortably on a laptop with 8–16 GB RAM (tested on an Apple M-series Mac).

## API
| Method | Path | Description |
|---|---|---|
| POST | `/api/documents` (multipart `file`) | Upload and index a PDF |
| GET | `/api/documents` | List indexed files |
| DELETE | `/api/documents/{file}` | Remove a file |
| POST | `/api/chat` `{"question": "..."}` | Answer + source excerpts |

## Configuration
| Variable | Default |
|---|---|
| `SERVER_ADDRESS` | `127.0.0.1` (this machine only; the app has no login) |
| `ALLOWED_HOSTS` | `localhost,127.0.0.1` |
| `AI_BASE_URL` | `http://localhost:11434/v1` (Ollama) |
| `AI_API_KEY` | empty (not needed for Ollama) |
| `CHAT_MODEL` | `llama3.2:3b` (try `llama3.1:8b` for better answers) |
| `EMBED_MODEL` | `nomic-embed-text` |

Chunk size, overlap and top-k are in `application.yml`.

## Security
- Each browser gets a private, random `HttpOnly; SameSite=Strict` cookie, and documents are stored per visitor, so visitors never see each other's files.
- Requests are only answered for the host names in `ALLOWED_HOSTS` (blocks DNS rebinding), and uploads from other websites are rejected.
- Locally it listens on `127.0.0.1` only. Limits: 5 MB and 20 pages per PDF, 3 PDFs per visitor, a server-wide memory cap, idle visitors cleared after 30 minutes, and per-IP rate limits (5 uploads and 30 questions per 10 minutes). When full, new uploads are refused; nobody's documents are evicted.

## Deploy (free, no credit card)
1. Get a free Gemini API key at [aistudio.google.com](https://aistudio.google.com).
2. On [Render](https://render.com) choose *New → Blueprint* and pick this repo. `render.yaml` sets Gemini as the AI and asks for `AI_API_KEY`.
3. Set `ALLOWED_HOSTS` to your Render host name.

Swapping providers needs no code changes: Ollama, Gemini and OpenAI all speak the same API here.

## Taking it to production
- **Persistent storage:** swap the in-memory `VectorStore` for PostgreSQL + pgvector.
- **Better answers:** set `AI_BASE_URL`, `AI_API_KEY` and the model names to a bigger hosted model.
- **Scanned PDFs:** add an OCR step (e.g. Tesseract) before chunking.
- **Auth and multi-tenant:** add Spring Security and scope documents per user.

## Tests
```bash
./mvnw test
```
Covers chunking and vector search (no model needed).
