package by.bsuir.adforecast.upload;

import by.bsuir.adforecast.security.AuthPrincipal;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/campaigns/{campaignId}/uploads")
public class UploadController {

    private final UploadService uploadService;
    private final ObjectMapper objectMapper;

    public UploadController(UploadService uploadService, ObjectMapper objectMapper) {
        this.uploadService = uploadService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('upload.manage')")
    public UploadResponse upload(@PathVariable("campaignId") long campaignId,
                                 @RequestParam("file") MultipartFile file,
                                 @RequestParam(value = "mapping", required = false) String mapping,
                                 Authentication authentication) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        return uploadService.upload(campaignId, principal.userId(), file, parseMapping(mapping));
    }

    @PostMapping("/analyze")
    @PreAuthorize("hasAuthority('upload.manage')")
    public AnalyzeResponse analyze(@PathVariable("campaignId") long campaignId,
                                   @RequestParam("file") MultipartFile file,
                                   Authentication authentication) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        return uploadService.analyze(campaignId, principal.userId(), file);
    }

    @GetMapping
    public List<UploadResponse> history(@PathVariable("campaignId") long campaignId, Authentication authentication) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        return uploadService.history(campaignId, principal.userId());
    }

    private Map<String, Integer> parseMapping(String mapping) {
        if (mapping == null || mapping.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(mapping, new TypeReference<Map<String, Integer>>() {
            });
        } catch (Exception exception) {
            throw new IllegalArgumentException("некорректное сопоставление колонок");
        }
    }
}
