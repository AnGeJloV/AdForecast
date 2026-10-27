package by.bsuir.adforecast.export;

import by.bsuir.adforecast.audit.AuditService;
import by.bsuir.adforecast.campaign.CampaignService;
import by.bsuir.adforecast.domain.AdAccount;
import by.bsuir.adforecast.domain.Campaign;
import by.bsuir.adforecast.domain.CampaignStatus;
import by.bsuir.adforecast.domain.Forecast;
import by.bsuir.adforecast.domain.ForecastParam;
import by.bsuir.adforecast.domain.ForecastResult;
import by.bsuir.adforecast.domain.ForecastStatus;
import by.bsuir.adforecast.domain.MlModel;
import by.bsuir.adforecast.domain.Organization;
import by.bsuir.adforecast.repo.CampaignRepository;
import by.bsuir.adforecast.repo.ExportHistoryRepository;
import by.bsuir.adforecast.repo.ForecastParamRepository;
import by.bsuir.adforecast.repo.ForecastRepository;
import by.bsuir.adforecast.repo.ForecastResultRepository;
import jakarta.persistence.EntityNotFoundException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExportServiceTest {

    @Mock
    private ForecastRepository forecastRepository;
    @Mock
    private ForecastParamRepository forecastParamRepository;
    @Mock
    private ForecastResultRepository forecastResultRepository;
    @Mock
    private CampaignRepository campaignRepository;
    @Mock
    private CampaignService campaignService;
    @Mock
    private ExportHistoryRepository exportHistoryRepository;
    @Mock
    private AuditService auditService;

    @InjectMocks
    private ExportService service;

    @BeforeEach
    void stubForecastData() {
        Campaign campaign = campaign();
        Forecast forecast = new Forecast();
        forecast.setId(100L);
        forecast.setCampaign(campaign);
        forecast.setStatus(ForecastStatus.COMPLETED);
        forecast.setStartedAt(LocalDateTime.of(2026, 10, 26, 9, 30));
        forecast.setModel(model("adforecast_roi", "v1"));

        ForecastParam param = new ForecastParam();
        param.setDurationDays(60);
        param.setHistDays(0);
        param.setBudget(new BigDecimal("10000.00"));
        param.setCurrency("BYN");

        ForecastResult result = new ForecastResult();
        result.setVerdict("PROFITABLE");
        result.setPaybackRatio(new BigDecimal("1.7000"));
        result.setForecastRoi(new BigDecimal("70.00"));
        result.setIntervalLower(new BigDecimal("1.4500"));
        result.setIntervalUpper(new BigDecimal("1.9500"));
        result.setAgreement(new BigDecimal("0.86"));
        result.setPaybackMonths(new BigDecimal("4.10"));
        result.setExpectedProfit(new BigDecimal("7000.00"));
        result.setBudgetAny(false);
        result.setBudgetMin(new BigDecimal("2700.00"));
        result.setBudgetMax(new BigDecimal("3084.00"));
        result.setMape(new BigDecimal("8.06"));

        lenient().when(forecastRepository.findByIdWithModel(100L)).thenReturn(Optional.of(forecast));
        lenient().when(campaignRepository.findWithAccount(1L)).thenReturn(Optional.of(campaign));
        lenient().when(forecastParamRepository.findByForecastId(100L)).thenReturn(Optional.of(param));
        lenient().when(forecastResultRepository.findByForecastId(100L)).thenReturn(Optional.of(result));
        lenient().when(forecastRepository.getReferenceById(100L)).thenReturn(forecast);
    }

    private Campaign campaign() {
        Organization organization = new Organization();
        organization.setId(1L);
        AdAccount account = new AdAccount();
        account.setAccountName("VK Реклама_кабинет");
        account.setPlatform("VK Реклама");
        account.setOrganization(organization);
        Campaign campaign = new Campaign();
        campaign.setId(1L);
        campaign.setAccount(account);
        campaign.setOrganization(organization);
        campaign.setCampaignName("VK — продвижение сообщества");
        campaign.setBudget(new BigDecimal("10000.00"));
        campaign.setCurrency("BYN");
        campaign.setStatus(CampaignStatus.ACTIVE);
        return campaign;
    }

    private MlModel model(String name, String version) {
        MlModel model = new MlModel();
        model.setModelName(name);
        model.setModelVersion(version);
        return model;
    }

    @Test
    void exportXlsxBuildsWorkbookWithReportValues() throws Exception {
        byte[] content = service.exportXlsx(100L, 5L);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            Sheet sheet = workbook.getSheet("Прогноз окупаемости");
            assertThat(sheet).isNotNull();
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).contains("AdForecast");
            List<String> labels = new java.util.ArrayList<>();
            for (int i = 2; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row != null && row.getCell(0) != null) {
                    labels.add(row.getCell(0).getStringCellValue());
                }
            }
            assertThat(labels).contains("Вердикт", "Прогнозный ROI, %", "MAPE модели, %");
            Row verdictRow = sheet.getRow(2 + labels.indexOf("Вердикт"));
            assertThat(verdictRow.getCell(1).getStringCellValue()).isEqualTo("вкладываться");
        }
        verify(exportHistoryRepository).save(any());
        verify(auditService).record(eq(5L), eq("выгрузка отчёта"), eq("прогноз"), eq(100L),
                any(String.class));
    }

    @Test
    void exportPdfProducesReadableCyrillicDocument() throws Exception {
        byte[] content = service.exportPdf(100L, 5L);

        try (PDDocument document = Loader.loadPDF(content)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("AdForecast");
            assertThat(text).contains("VK — продвижение сообщества");
            assertThat(text).contains("вкладываться");
            assertThat(text).contains("1.7000");
        }
        verify(exportHistoryRepository).save(any());
    }

    @Test
    void warningVerdictIsExportedWithoutNumbers() {
        ForecastResult result = new ForecastResult();
        result.setVerdict("BELOW_MINIMUM");
        when(forecastResultRepository.findByForecastId(100L)).thenReturn(Optional.of(result));

        byte[] content = service.exportXlsx(100L, 5L);

        assertThat(content).isNotEmpty();
        verify(auditService).record(eq(5L), eq("выгрузка отчёта"), eq("прогноз"), eq(100L),
                any(String.class));
    }

    @Test
    void exportRequiresCompletedForecast() {
        Forecast forecast = new Forecast();
        forecast.setId(100L);
        forecast.setCampaign(campaign());
        forecast.setStatus(ForecastStatus.RUNNING);
        when(forecastRepository.findByIdWithModel(100L)).thenReturn(Optional.of(forecast));

        assertThatThrownBy(() -> service.exportXlsx(100L, 5L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("экспорт невозможен");
    }

    @Test
    void exportRequiresAccess() {
        doThrow(new AccessDeniedException("нет доступа к кампании"))
                .when(campaignService).requireAccess(5L, 1L);

        assertThatThrownBy(() -> service.exportPdf(100L, 5L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void exportRequiresExistingForecast() {
        when(forecastRepository.findByIdWithModel(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.exportXlsx(404L, 5L))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("прогноз не найден");
    }
}
