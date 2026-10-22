package by.bsuir.adforecast.export;

import by.bsuir.adforecast.campaign.CampaignService;
import by.bsuir.adforecast.domain.Campaign;
import by.bsuir.adforecast.domain.ExportFormat;
import by.bsuir.adforecast.domain.ExportHistory;
import by.bsuir.adforecast.domain.Forecast;
import by.bsuir.adforecast.domain.ForecastParam;
import by.bsuir.adforecast.domain.ForecastResult;
import by.bsuir.adforecast.domain.ForecastStatus;
import by.bsuir.adforecast.repo.CampaignRepository;
import by.bsuir.adforecast.repo.ExportHistoryRepository;
import by.bsuir.adforecast.repo.ForecastParamRepository;
import by.bsuir.adforecast.repo.ForecastRepository;
import by.bsuir.adforecast.repo.ForecastResultRepository;
import jakarta.persistence.EntityNotFoundException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;

@Service
public class ExportService {

    private final ForecastRepository forecastRepository;
    private final ForecastParamRepository forecastParamRepository;
    private final ForecastResultRepository forecastResultRepository;
    private final CampaignRepository campaignRepository;
    private final CampaignService campaignService;
    private final ExportHistoryRepository exportHistoryRepository;

    public ExportService(ForecastRepository forecastRepository,
                         ForecastParamRepository forecastParamRepository,
                         ForecastResultRepository forecastResultRepository,
                         CampaignRepository campaignRepository,
                         CampaignService campaignService,
                         ExportHistoryRepository exportHistoryRepository) {
        this.forecastRepository = forecastRepository;
        this.forecastParamRepository = forecastParamRepository;
        this.forecastResultRepository = forecastResultRepository;
        this.campaignRepository = campaignRepository;
        this.campaignService = campaignService;
        this.exportHistoryRepository = exportHistoryRepository;
    }

    public byte[] exportXlsx(long forecastId, long userId) {
        ForecastReport report = prepareReport(forecastId, userId);
        byte[] content = buildXlsx(report);
        saveHistory(forecastId, ExportFormat.XLSX);
        return content;
    }

    public byte[] exportPdf(long forecastId, long userId) {
        ForecastReport report = prepareReport(forecastId, userId);
        byte[] content = buildPdf(report);
        saveHistory(forecastId, ExportFormat.PDF);
        return content;
    }

    private ForecastReport prepareReport(long forecastId, long userId) {
        Forecast forecast = forecastRepository.findByIdWithModel(forecastId)
                .orElseThrow(() -> new EntityNotFoundException("прогноз не найден"));
        Campaign campaign = campaignRepository.findWithAccount(forecast.getCampaign().getId())
                .orElseThrow(() -> new EntityNotFoundException("кампания не найдена"));
        campaignService.requireAccess(userId, campaign.getId());
        if (forecast.getStatus() != ForecastStatus.COMPLETED) {
            throw new IllegalStateException("прогноз не завершен, экспорт невозможен");
        }
        ForecastParam param = forecastParamRepository.findByForecastId(forecastId)
                .orElseThrow(() -> new EntityNotFoundException("параметры прогноза не найдены"));
        ForecastResult result = forecastResultRepository.findByForecastId(forecastId)
                .orElseThrow(() -> new EntityNotFoundException("результат прогноза не найден"));

        String paybackMonths = result.getPaybackMonths() == null
                ? "не окупается в горизонте планирования"
                : result.getPaybackMonths().toPlainString();
        return new ForecastReport(
                forecast.getId(),
                forecast.getStartedAt(),
                campaign.getCampaignName(),
                campaign.getId(),
                campaign.getAccount().getAccountName(),
                campaign.getAccount().getPlatform(),
                campaign.getBudget().toPlainString() + " " + campaign.getCurrency(),
                campaign.getStatus().name(),
                param.getHorizonDays(),
                param.getConfidenceLevel().toPlainString(),
                param.getUseSeasonality() ? "да" : "нет",
                result.getForecastRoi() == null ? "" : result.getForecastRoi().toPlainString(),
                paybackMonths,
                formatProbability(result.getPaybackProbability()),
                forecast.getModel().getModelVersion(),
                result.getMape() == null ? "" : result.getMape().toPlainString()
        );
    }

    private String formatProbability(BigDecimal probability) {
        if (probability == null) {
            return "";
        }
        return String.format(Locale.US, "%.2f", probability.doubleValue());
    }

    private byte[] buildXlsx(ForecastReport report) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Прогноз окупаемости");
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFont(headerFont);
            CellStyle labelStyle = workbook.createCellStyle();
            labelStyle.setFont(headerFont);
            labelStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            labelStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            Row titleRow = sheet.createRow(0);
            Cell title = titleRow.createCell(0);
            title.setCellValue("AdForecast. Отчет о прогнозе окупаемости кампании");
            title.setCellStyle(headerStyle);

            int rowIndex = 2;
            for (String[] row : report.rows()) {
                Row current = sheet.createRow(rowIndex++);
                Cell label = current.createCell(0);
                label.setCellValue(row[0]);
                label.setCellStyle(labelStyle);
                current.createCell(1).setCellValue(row[1]);
            }
            sheet.setColumnWidth(0, 12 * 256);
            sheet.setColumnWidth(1, 46 * 256);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("не удалось сформировать XLSX-файл", exception);
        }
    }

    private byte[] buildPdf(ForecastReport report) {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDType0Font font = loadFont(document, "fonts/DejaVuSans.ttf");
            PDType0Font bold = loadFont(document, "fonts/DejaVuSans-Bold.ttf");
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                float y = 790;
                content.beginText();
                content.setFont(bold, 16);
                content.newLineAtOffset(50, y);
                content.showText("AdForecast");
                content.endText();
                y -= 26;
                content.beginText();
                content.setFont(bold, 13);
                content.newLineAtOffset(50, y);
                content.showText("Отчет о прогнозе окупаемости кампании");
                content.endText();
                y -= 30;
                for (String[] row : report.rows()) {
                    content.beginText();
                    content.setFont(bold, 11);
                    content.newLineAtOffset(50, y);
                    content.showText(row[0]);
                    content.endText();
                    content.beginText();
                    content.setFont(font, 11);
                    content.newLineAtOffset(230, y);
                    content.showText(row[1]);
                    content.endText();
                    y -= 20;
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("не удалось сформировать PDF-файл", exception);
        }
    }

    private PDType0Font loadFont(PDDocument document, String path) throws IOException {
        try (InputStream input = new ClassPathResource(path).getInputStream()) {
            return PDType0Font.load(document, input);
        }
    }

    private void saveHistory(long forecastId, ExportFormat format) {
        ExportHistory history = new ExportHistory();
        history.setForecast(forecastRepository.getReferenceById(forecastId));
        history.setExportFormat(format);
        history.setExportDate(LocalDateTime.now());
        exportHistoryRepository.save(history);
    }
}
