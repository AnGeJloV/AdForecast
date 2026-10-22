package by.bsuir.adforecast.upload;

import by.bsuir.adforecast.security.AuthPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/uploads")
public class UploadsController {

    private final UploadService uploadService;

    public UploadsController(UploadService uploadService) {
        this.uploadService = uploadService;
    }

    @GetMapping
    public List<UploadListItem> historyAll(Authentication authentication) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        return uploadService.historyAll(principal.userId());
    }
}
