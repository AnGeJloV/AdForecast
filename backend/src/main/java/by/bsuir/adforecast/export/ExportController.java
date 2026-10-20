package by.bsuir.adforecast.export;

import by.bsuir.adforecast.security.AuthPrincipal;
import by.bsuir.adforecast.security.Roles;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/forecasts/{forecastId}/export")
public class ExportController {


    private static final MediaType XLSX_TYPE =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private static final MediaType PDF_TYPE = MediaType.APPLICATION_PDF;

    private final ExportService exportService;

    public ExportController(ExportService exportService) {
        this.exportService = exportService;
    }

    @GetMapping("/xlsx")
    @PreAuthorize("hasAuthority('export.run')")
    public ResponseEntity<byte[]> exportXlsx(@PathVariable("forecastId") long forecastId,
                                             Authentication authentication) {
        return respond(forecastId, authentication, exportService.exportXlsx(
                forecastId, Roles.isAdmin(authentication), principal(authentication).userId()), "xlsx", XLSX_TYPE);
    }

    @GetMapping("/pdf")
    @PreAuthorize("hasAuthority('export.run')")
    public ResponseEntity<byte[]> exportPdf(@PathVariable("forecastId") long forecastId,
                                            Authentication authentication) {
        return respond(forecastId, authentication, exportService.exportPdf(
                forecastId, Roles.isAdmin(authentication), principal(authentication).userId()), "pdf", PDF_TYPE);
    }

    private ResponseEntity<byte[]> respond(long forecastId, Authentication authentication,
                                           byte[] content, String extension, MediaType mediaType) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=forecast_" + forecastId + "." + extension)
                .contentType(mediaType)
                .body(content);
    }

    private AuthPrincipal principal(Authentication authentication) {
        return (AuthPrincipal) authentication.getPrincipal();
    }

}
