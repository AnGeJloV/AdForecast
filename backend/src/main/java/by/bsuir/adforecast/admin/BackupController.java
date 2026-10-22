package by.bsuir.adforecast.admin;

import by.bsuir.adforecast.audit.AuditService;
import by.bsuir.adforecast.security.AuthPrincipal;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/admin/backups")
public class BackupController {

    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]+");

    private final Path backupsDir;
    private final AuditService auditService;

    public BackupController(@Value("${adforecast.backups-dir}") String backupsDir, AuditService auditService) {
        this.backupsDir = Paths.get(backupsDir);
        this.auditService = auditService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('backup.manage')")
    public List<BackupEntryResponse> list() throws IOException {
        if (!Files.isDirectory(backupsDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(backupsDir)) {
            return files.filter(BackupController::isBackupFile)
                    .map(path -> new BackupEntryResponse(
                            path.getFileName().toString(),
                            fileSize(path),
                            LocalDateTime.ofInstant(
                                    path.toFile().lastModified() == 0
                                            ? java.time.Instant.EPOCH
                                            : java.time.Instant.ofEpochMilli(path.toFile().lastModified()),
                                    ZoneId.systemDefault())))
                    .sorted(java.util.Comparator.comparing(BackupEntryResponse::lastModified).reversed())
                    .toList();
        }
    }

    @GetMapping("/{name}/download")
    @PreAuthorize("hasAuthority('backup.manage')")
    @Transactional
    public ResponseEntity<Resource> download(@PathVariable("name") String name, Authentication authentication) throws IOException {
        if (!SAFE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("недопустимое имя файла");
        }
        Path path = backupsDir.resolve(name);
        if (!isBackupFile(path)) {
            throw new EntityNotFoundException("резервная копия не найдена");
        }
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        auditService.record(principal.userId(), "скачивание резервной копии", "файл", null, name);
        Resource resource = new FileSystemResource(path);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    private static boolean isBackupFile(Path path) {
        String name = path.getFileName().toString();
        return Files.isRegularFile(path) && (name.endsWith(".sql") || name.endsWith(".gz"));
    }

    private long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException exception) {
            return 0;
        }
    }
}
