package com.pushpakraut.docschat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** No AI calls here: every request is stopped (or answered) before the model is needed. */
@SpringBootTest(properties = {"app.allowed-hosts=localhost,docs.example.com", "app.limits.uploads-per-ip=2"})
@AutoConfigureMockMvc
class RequestGuardTest {
    @Autowired MockMvc mvc;

    @Test
    void unknownHostIsRejected() throws Exception {
        mvc.perform(get("/api/documents").header("Host", "evil.example")).andExpect(status().isBadRequest());
    }

    @Test
    void crossSiteUploadIsRejected() throws Exception {
        mvc.perform(multipart("/api/documents").file(new MockMultipartFile("file", "x.pdf", "application/pdf", new byte[]{1}))
                        .header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden());
    }

    @Test
    void ownSiteGetsVisitorCookie() throws Exception {
        mvc.perform(get("/api/documents").header("Host", "docs.example.com"))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", containsString("HttpOnly; SameSite=Strict")))
                .andExpect(content().json("[]"));
    }

    @Test
    void uploadsAreRateLimitedPerIp() throws Exception {
        var file = new MockMultipartFile("file", "x.txt", "text/plain", new byte[]{1}); // rejected as non-PDF, but still counted
        for (int i = 0; i < 2; i++) {
            mvc.perform(multipart("/api/documents").file(file).with(r -> { r.setRemoteAddr("10.0.0.9"); return r; }))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(multipart("/api/documents").file(file).with(r -> { r.setRemoteAddr("10.0.0.9"); return r; }))
                .andExpect(status().isTooManyRequests());
        mvc.perform(multipart("/api/documents").file(file).with(r -> { r.setRemoteAddr("10.0.0.10"); return r; }))
                .andExpect(status().isBadRequest()); // a different IP is unaffected
    }
}
