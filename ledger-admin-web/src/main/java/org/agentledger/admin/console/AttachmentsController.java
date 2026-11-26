// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.admin.console;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The download of an attachment through the application (read only). It is a page of the console like the others: the security
 * chain lets only the signed-in owner reach it. The body is the decrypted file, always sent as {@code application/octet-stream}
 * with {@code Content-Disposition: attachment} and {@code nosniff}, so a browser saves it and never renders it. No path and no
 * storage key is in the URL, the headers or the log.
 */
@Controller
public class AttachmentsController {

    private final AttachmentPanels panels;
    private final AttachmentHealth health;

    public AttachmentsController(AttachmentPanels panels, AttachmentHealth health) {
        this.panels = panels;
        this.health = health;
    }

    /** The health of the attachments: the read-only result of the attachment check (nothing is moved). */
    @GetMapping("/attachments")
    String health(Model model) {
        model.addAttribute("health", health.health());
        return "attachments";
    }

    @GetMapping("/attachments/{id}/download")
    void download(@PathVariable("id") String id,
            @RequestParam(name = "seq", required = false) Integer seq,
            HttpServletResponse response) throws IOException {
        int fileSeq = seq == null ? 0 : seq;
        AttachmentPanels.Download download = panels.download(id, fileSeq);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setContentLengthLong(download.sizeBytes());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(download.fileName(), StandardCharsets.UTF_8).build().toString());
        response.setHeader("X-Content-Type-Options", "nosniff");
        panels.copy(download, response.getOutputStream());
    }
}
