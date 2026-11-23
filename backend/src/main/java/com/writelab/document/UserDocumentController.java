package com.writelab.document;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/documents")
public class UserDocumentController {

    private final UserDocumentService userDocumentService;

    public UserDocumentController(UserDocumentService userDocumentService) {
        this.userDocumentService = userDocumentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DocumentUploadResponse upload(@RequestParam("file") MultipartFile file) {
        return userDocumentService.upload(file);
    }

    @GetMapping
    public List<UserDocumentSummary> list() {
        return userDocumentService.list();
    }

    @GetMapping("/{id}")
    public UserDocumentSummary find(@PathVariable UUID id) {
        return userDocumentService.find(id);
    }

    @PostMapping("/{id}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public UserDocumentSummary retry(@PathVariable UUID id) {
        return userDocumentService.retry(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        userDocumentService.delete(id);
    }

    @PatchMapping("/{id}")
    public UserDocumentSummary updateActive(
            @PathVariable UUID id,
            @RequestBody(required = false) UpdateUserDocumentRequest request
    ) {
        return userDocumentService.setActive(id, request == null ? null : request.active());
    }
}
