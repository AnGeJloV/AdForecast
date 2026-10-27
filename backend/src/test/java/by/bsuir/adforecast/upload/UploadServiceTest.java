package by.bsuir.adforecast.upload;

import by.bsuir.adforecast.audit.AuditService;
import by.bsuir.adforecast.campaign.CampaignService;
import by.bsuir.adforecast.domain.Campaign;
import by.bsuir.adforecast.domain.DailyMetric;
import by.bsuir.adforecast.domain.Upload;
import by.bsuir.adforecast.domain.UploadFormat;
import by.bsuir.adforecast.repo.CampaignRepository;
import by.bsuir.adforecast.repo.DailyMetricRepository;
import by.bsuir.adforecast.repo.UploadRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UploadServiceTest {

    private static final byte[] CSV_BYTES = "date,impressions,clicks,cost,revenue\n2026-08-01,10,1,1,1\n"
            .getBytes(StandardCharsets.UTF_8);

    @Mock
    private CampaignRepository campaignRepository;
    @Mock
    private CampaignService campaignService;
    @Mock
    private UploadRepository uploadRepository;
    @Mock
    private DailyMetricRepository dailyMetricRepository;
    @Mock
    private StatsParser statsParser;
    @Mock
    private AuditService auditService;
    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private UploadService service;

    private MockMultipartFile csvFile() {
        return new MockMultipartFile("file", "report.csv", "text/csv", CSV_BYTES);
    }

    private AnalyzeResponse analysis() {
        return new AnalyzeResponse("CSV", ",", "UTF-8", "ГГГГ-ММ-ДД",
                List.of(new AnalyzeResponse.ColumnDef(0, "date", "date", "по названию")),
                List.of(List.of("2026-08-01", "10", "1", "1", "1")),
                1, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 1), List.of(), List.of());
    }

    private Upload savedUpload() {
        Upload upload = new Upload();
        upload.setId(7L);
        upload.setFileName("report.csv");
        upload.setFileFormat(UploadFormat.CSV);
        upload.setPeriodStart(LocalDate.of(2026, 8, 1));
        upload.setPeriodEnd(LocalDate.of(2026, 8, 1));
        upload.setUploadedAt(LocalDateTime.of(2026, 10, 27, 12, 0));
        return upload;
    }

    @Test
    void analyzeRequiresExistingCampaign() {
        when(campaignRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.analyze(1L, 5L, csvFile()))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void analyzeRequiresAccess() {
        when(campaignRepository.findById(1L)).thenReturn(Optional.of(new Campaign()));
        doThrow(new AccessDeniedException("нет доступа к кампании"))
                .when(campaignService).requireAccess(5L, 1L);

        assertThatThrownBy(() -> service.analyze(1L, 5L, csvFile()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void analyzeRejectsEmptyFile() {
        when(campaignRepository.findById(1L)).thenReturn(Optional.of(new Campaign()));

        assertThatThrownBy(() -> service.analyze(1L, 5L,
                new MockMultipartFile("file", new byte[0])))
                .isInstanceOf(UploadException.class)
                .hasMessageContaining("файл пуст");
    }

    @Test
    void analyzeReturnsParserResultWhenPeriodIsFree() throws IOException {
        when(campaignRepository.findById(1L)).thenReturn(Optional.of(new Campaign()));
        when(statsParser.detectFormat(any())).thenReturn(UploadFormat.CSV);
        when(statsParser.analyze(eq(UploadFormat.CSV), any())).thenReturn(analysis());
        when(dailyMetricRepository.existsInPeriod(eq(1L), any(), any())).thenReturn(false);

        AnalyzeResponse response = service.analyze(1L, 5L, csvFile());

        assertThat(response.rowCount()).isEqualTo(1);
        assertThat(response.warnings()).isEmpty();
    }

    @Test
    void analyzeWarnsAboutPeriodOverlap() throws IOException {
        when(campaignRepository.findById(1L)).thenReturn(Optional.of(new Campaign()));
        when(statsParser.detectFormat(any())).thenReturn(UploadFormat.CSV);
        when(statsParser.analyze(eq(UploadFormat.CSV), any())).thenReturn(analysis());
        when(dailyMetricRepository.existsInPeriod(eq(1L), any(), any())).thenReturn(true);

        AnalyzeResponse response = service.analyze(1L, 5L, csvFile());

        assertThat(response.warnings())
                .anySatisfy(warning -> assertThat(warning).contains("пересекается"));
    }

    @Test
    void analyzeWrapsIoFailureIntoUploadException() throws IOException {
        when(campaignRepository.findById(1L)).thenReturn(Optional.of(new Campaign()));
        when(statsParser.detectFormat(any())).thenThrow(new IOException("поток закрыт"));

        assertThatThrownBy(() -> service.analyze(1L, 5L, csvFile()))
                .isInstanceOf(UploadException.class)
                .hasMessageContaining("не удалось прочитать файл");
    }

    @Test
    void uploadRequiresExistingCampaign() {
        when(campaignRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upload(1L, 5L, csvFile(), null))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void uploadRejectsParserErrors() throws IOException {
        when(campaignRepository.findById(1L)).thenReturn(Optional.of(new Campaign()));
        when(statsParser.detectFormat(any())).thenReturn(UploadFormat.CSV);
        when(statsParser.parse(eq(UploadFormat.CSV), any(), any()))
                .thenReturn(new ParsedRows(List.of(), List.of("строка 2: некорректное число abc")));

        assertThatThrownBy(() -> service.upload(1L, 5L, csvFile(), null))
                .isInstanceOf(UploadException.class)
                .hasMessageContaining("строка 2");
    }

    @Test
    void uploadRejectsFileWithoutDataRows() throws IOException {
        when(campaignRepository.findById(1L)).thenReturn(Optional.of(new Campaign()));
        when(statsParser.detectFormat(any())).thenReturn(UploadFormat.CSV);
        when(statsParser.parse(eq(UploadFormat.CSV), any(), any()))
                .thenReturn(new ParsedRows(List.of(), List.of()));

        assertThatThrownBy(() -> service.upload(1L, 5L, csvFile(), null))
                .isInstanceOf(UploadException.class)
                .hasMessageContaining("не содержит строк с показателями");
    }

    @Test
    void uploadRejectsPeriodOverlap() throws IOException {
        when(campaignRepository.findById(1L)).thenReturn(Optional.of(new Campaign()));
        when(statsParser.detectFormat(any())).thenReturn(UploadFormat.CSV);
        when(statsParser.parse(eq(UploadFormat.CSV), any(), any())).thenReturn(parsedRows());
        when(dailyMetricRepository.existsInPeriod(eq(1L), any(), any())).thenReturn(true);

        assertThatThrownBy(() -> service.upload(1L, 5L, csvFile(), null))
                .isInstanceOf(UploadException.class)
                .hasMessageContaining("пересекается");
    }

    @Test
    void uploadSavesMetricsAndRecordsAudit() throws IOException {
        when(campaignRepository.findById(1L)).thenReturn(Optional.of(new Campaign()));
        when(statsParser.detectFormat(any())).thenReturn(UploadFormat.CSV);
        when(statsParser.parse(eq(UploadFormat.CSV), any(), any())).thenReturn(parsedRows());
        when(dailyMetricRepository.existsInPeriod(eq(1L), any(), any())).thenReturn(false);
        when(uploadRepository.save(any(Upload.class)))
                .thenAnswer(invocation -> {
                    Upload saved = invocation.getArgument(0);
                    saved.setId(7L);
                    return saved;
                });

        UploadResponse response = service.upload(1L, 5L, csvFile(), Map.of("date", 0));

        assertThat(response.rowsCount()).isEqualTo(2);
        assertThat(response.fileName()).isEqualTo("report.csv");
        ArgumentCaptor<List<DailyMetric>> metrics = ArgumentCaptor.forClass(List.class);
        verify(dailyMetricRepository).saveAll(metrics.capture());
        assertThat(metrics.getValue()).hasSize(2);
        verify(auditService).record(eq(5L), eq("загрузка показателей"), eq("кампания"), eq(1L),
                contains("report.csv"));
    }

    @Test
    void uploadCleansWindowsPathInFileName() throws IOException {
        when(campaignRepository.findById(1L)).thenReturn(Optional.of(new Campaign()));
        when(statsParser.detectFormat(any())).thenReturn(UploadFormat.CSV);
        when(statsParser.parse(eq(UploadFormat.CSV), any(), any())).thenReturn(parsedRows());
        when(dailyMetricRepository.existsInPeriod(eq(1L), any(), any())).thenReturn(false);
        when(uploadRepository.save(any(Upload.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.upload(1L, 5L, new MockMultipartFile("file", "C:\\отчеты\\report.csv",
                "text/csv", CSV_BYTES), null);

        verify(uploadRepository).save(any(Upload.class));
        verify(auditService).record(anyLong(), anyString(), anyString(), anyLong(), contains("report.csv"));
    }

    @Test
    void uploadFallsBackToGeneratedNameWhenOriginalMissing() throws IOException {
        when(campaignRepository.findById(1L)).thenReturn(Optional.of(new Campaign()));
        when(statsParser.detectFormat(any())).thenReturn(UploadFormat.CSV);
        when(statsParser.parse(eq(UploadFormat.CSV), any(), any())).thenReturn(parsedRows());
        when(dailyMetricRepository.existsInPeriod(eq(1L), any(), any())).thenReturn(false);
        when(uploadRepository.save(any(Upload.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.upload(1L, 5L, new MockMultipartFile("file", CSV_BYTES), null);

        verify(uploadRepository).save(any(Upload.class));
        verify(auditService).record(anyLong(), anyString(), anyString(), anyLong(), contains("upload.csv"));
    }

    @Test
    void historyReturnsUploadsWithRowCounts() {
        when(campaignRepository.findById(1L)).thenReturn(Optional.of(new Campaign()));
        when(uploadRepository.findByCampaignIdOrderByIdDesc(1L)).thenReturn(List.of(savedUpload()));
        when(dailyMetricRepository.countByUploadId(7L)).thenReturn(5L);

        List<UploadResponse> history = service.history(1L, 5L);

        assertThat(history).hasSize(1);
        assertThat(history.get(0).rowsCount()).isEqualTo(5L);
    }

    @Test
    void historyAllMapsRowsFromDatabase() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getLong("upload_id")).thenReturn(7L);
        when(resultSet.getLong("campaign_id")).thenReturn(1L);
        when(resultSet.getString("campaign_name")).thenReturn("Кампания");
        when(resultSet.getString("file_name")).thenReturn("report.csv");
        when(resultSet.getString("file_format")).thenReturn("CSV");
        when(resultSet.getDate("period_start")).thenReturn(Date.valueOf("2026-08-01"));
        when(resultSet.getDate("period_end")).thenReturn(Date.valueOf("2026-08-31"));
        when(resultSet.getTimestamp("uploaded_at")).thenReturn(Timestamp.valueOf("2026-10-27 12:00:00"));
        when(resultSet.getLong("rows_count")).thenReturn(31L);

        service.historyAll(5L);

        ArgumentCaptor<RowMapper<UploadListItem>> mapper = ArgumentCaptor.forClass(RowMapper.class);
        verify(jdbcTemplate).query(anyString(), mapper.capture(), eq(5L));
        UploadListItem item = mapper.getValue().mapRow(resultSet, 0);
        assertThat(item.id()).isEqualTo(7L);
        assertThat(item.campaignName()).isEqualTo("Кампания");
        assertThat(item.periodEnd()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(item.rowsCount()).isEqualTo(31L);
    }

    private ParsedRows parsedRows() {
        return new ParsedRows(List.of(
                new MetricRow(LocalDate.of(2026, 8, 1), 10, 1, new BigDecimal("1"), new BigDecimal("1")),
                new MetricRow(LocalDate.of(2026, 8, 2), 20, 2, new BigDecimal("2"), new BigDecimal("3"))
        ), List.of());
    }
}
