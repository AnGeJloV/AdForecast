package by.bsuir.adforecast.export;

import by.bsuir.adforecast.audit.AuditService;
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
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class ExportService {

    private final ForecastRepository forecastRepository;
    private final ForecastParamRepository forecastParamRepository;
    private final ForecastResultRepository forecastResultRepository;
    private final CampaignRepository campaignRepository;
    private final CampaignService campaignService;
    private final ExportHistoryRepository exportHistoryRepository;
    private final AuditService auditService;

    public ExportService(ForecastRepository forecastRepository,
                         ForecastParamRepository forecastParamRepository,
                         ForecastResultRepository forecastResultRepository,
                         CampaignRepository campaignRepository,
                         CampaignService campaignService,
                         ExportHistoryRepository exportHistoryRepository,
                         AuditService auditService) {
        this.forecastRepository = forecastRepository;
        this.forecastParamRepository = forecastParamRepository;
        this.forecastResultRepository = forecastResultRepository;
        this.campaignRepository = campaignRepository;
        this.campaignService = campaignService;
        this.exportHistoryRepository = exportHistoryRepository;
        this.auditService = auditService;
    }

    @Transactional
    public byte[] exportXlsx(long forecastId, long userId) {
        ForecastReport report = prepareReport(forecastId, userId);
        byte[] content = buildXlsx(report);
        saveHistory(forecastId, ExportFormat.XLSX);
        auditService.record(userId, "выгрузка отчёта", "прогноз", forecastId,
                "формат XLSX, кампания «" + report.campaignName() + "»");
        return content;
    }

    @Transactional
    public byte[] exportPdf(long forecastId, long userId) {
        ForecastReport report = prepareReport(forecastId, userId);
        byte[] content = buildPdf(report);
        saveHistory(forecastId, ExportFormat.PDF);
        auditService.record(userId, "выгрузка отчёта", "прогноз", forecastId,
                "формат PDF, кампания «" + report.campaignName() + "»");
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

        boolean numeric = numericVerdict(result.getVerdict());
        String paybackMonths = !numeric
                ? "не рассчитывался"
                : result.getPaybackMonths() == null
                        ? "не окупается в течение кампании"
                        : result.getPaybackMonths().toPlainString();
        String verdict = switch (result.getVerdict() == null ? "" : result.getVerdict()) {
            case "PROFITABLE" -> "вкладываться";
            case "BORDERLINE" -> "на грани окупаемости";
            case "UNPROFITABLE" -> "убыточно";
            case "OUT_OF_RANGE" -> "прогноз недостоверен: бюджет вне данных модели";
            case "BELOW_MINIMUM" -> "бюджет ниже минимума площадки";
            case "NO_CHANNEL_DATA" -> "по каналу нет данных для прогноза";
            default -> "—";
        };
        String budgetRange;
        if (!numeric) {
            budgetRange = "не рассчитывался";
        } else if (Boolean.TRUE.equals(result.getBudgetAny())) {
            budgetRange = "окупается при любом разумном бюджете в пределах данных модели";
        } else if (result.getBudgetMin() == null && result.getBudgetMax() == null) {
            budgetRange = "не рассчитан";
        } else {
            budgetRange = (result.getBudgetMin() == null ? "от минимального" : result.getBudgetMin().toPlainString())
                    + " — "
                    + (result.getBudgetMax() == null ? "без ограничения" : result.getBudgetMax().toPlainString());
        }
        String history = param.getHistDays() == 0
                ? "нет (кампания планируемая)"
                : param.getHistDays() + " дней, расход "
                        + (param.getHistCost() == null ? "—" : param.getHistCost().toPlainString())
                        + ", доход "
                        + (param.getHistRevenue() == null ? "—" : param.getHistRevenue().toPlainString());
        String modelInfo = forecast.getModel() == null
                ? "не назначена"
                : "основная: " + forecast.getModel().getModelName() + " " + forecast.getModel().getModelVersion();
        String snapshotBudget = (param.getBudget() == null ? campaign.getBudget() : param.getBudget()).toPlainString()
                + " " + (param.getCurrency() == null ? campaign.getCurrency() : param.getCurrency());
        if (result.getOwnModel() != null) {
            modelInfo += ", своя: " + result.getOwnModel().getModelVersion()
                    + " (вес " + result.getOwnWeight().toPlainString() + ")";
        }
        return new ForecastReport(
                forecast.getId(),
                forecast.getStartedAt(),
                campaign.getCampaignName(),
                campaign.getId(),
                campaign.getAccount().getAccountName(),
                campaign.getAccount().getPlatform(),
                snapshotBudget,
                campaign.getStatus().name(),
                verdict,
                result.getPaybackRatio() == null ? "" : result.getPaybackRatio().toPlainString(),
                result.getForecastRoi() == null ? "" : result.getForecastRoi().toPlainString(),
                result.getIntervalLower() == null ? "" : result.getIntervalLower().toPlainString(),
                result.getIntervalUpper() == null ? "" : result.getIntervalUpper().toPlainString(),
                result.getAgreement() == null
                        ? ""
                        : String.format(Locale.US, "%.0f", result.getAgreement().doubleValue() * 100),
                paybackMonths,
                result.getExpectedProfit() == null ? "" : result.getExpectedProfit().toPlainString(),
                budgetRange,
                String.valueOf(param.getDurationDays()),
                history,
                modelInfo,
                result.getMape() == null ? "" : result.getMape().toPlainString()
        );
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
            PDType0Font font = loadFont(document, "fonts/DejaVuSans.ttf");
            PDType0Font bold = loadFont(document, "fonts/DejaVuSans-Bold.ttf");
            PDRectangle box = PDRectangle.A4;
            float pageWidth = box.getWidth();
            float pageHeight = box.getHeight();
            float marginLeft = 50;
            float marginRight = 50;
            float lineHeight = 18;
            float labelSize = 11, valueSize = 11, titleSize = 13, brandSize = 16;

            List<String[]> rows = report.rows();

            // колонка значений начинается после самой длинной метки — без наложения
            float maxLabelWidth = 0;
            for (String[] row : rows) {
                maxLabelWidth = Math.max(maxLabelWidth, measure(row[0], bold, labelSize));
            }
            float valueX = marginLeft + maxLabelWidth + 18;
            float valueWidth = Math.max(50, (pageWidth - marginRight) - valueX);

            PDPage page = new PDPage(box);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                float y = pageHeight - 70;
                content.beginText();
                content.setFont(bold, brandSize);
                content.newLineAtOffset(marginLeft, y);
                content.showText("AdForecast");
                content.endText();
                y -= 28;
                content.beginText();
                content.setFont(bold, titleSize);
                content.newLineAtOffset(marginLeft, y);
                content.showText("Отчет о прогнозе окупаемости кампании");
                content.endText();
                y -= 30;

                for (String[] row : rows) {
                    List<String> values = wrap(row[1] == null ? "" : row[1], font, valueSize, valueWidth);
                    int lines = Math.max(1, values.size());
                    if (y - lines * lineHeight < 50) {
                        break;
                    }
                    content.beginText();
                    content.setFont(bold, labelSize);
                    content.newLineAtOffset(marginLeft, y);
                    content.showText(row[0]);
                    content.endText();
                    content.beginText();
                    content.setFont(font, valueSize);
                    content.newLineAtOffset(valueX, y);
                    content.showText(values.get(0));
                    content.endText();
                    y -= lineHeight;
                    for (int i = 1; i < values.size(); i++) {
                        content.beginText();
                        content.setFont(font, valueSize);
                        content.newLineAtOffset(valueX, y);
                        content.showText(values.get(i));
                        content.endText();
                        y -= lineHeight;
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("не удалось сформировать PDF-файл", exception);
        }
    }

    /** Ширина текста в пунктах при заданном кегле. */
    private static float measure(String text, PDType0Font font, float size) throws IOException {
        return text == null ? 0 : font.getStringWidth(text) / 1000f * size;
    }

    /** Перенос длинного значения по словам, чтобы не уходило за правый край и не наезжало.
     *  Слишком длинное слово вписывается принудительно. */
    private static List<String> wrap(String text, PDType0Font font, float size, float maxWidth) throws IOException {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            String probe = current.length() == 0 ? word : current + " " + word;
            if (current.length() == 0 || font.getStringWidth(probe) / 1000f * size <= maxWidth) {
                if (current.length() > 0) {
                    current.append(' ');
                }
                current.append(word);
            } else {
                lines.add(current.toString());
                current.setLength(0);
                current.append(word);
            }
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        return lines;
    }

    private PDType0Font loadFont(PDDocument document, String path) throws IOException {
        try (InputStream input = new ClassPathResource(path).getInputStream()) {
            return PDType0Font.load(document, input);
        }
    }

    private static boolean numericVerdict(String verdict) {
        return switch (verdict == null ? "" : verdict) {
            case "PROFITABLE", "BORDERLINE", "UNPROFITABLE" -> true;
            default -> false;
        };
    }

    private void saveHistory(long forecastId, ExportFormat format) {
        ExportHistory history = new ExportHistory();
        history.setForecast(forecastRepository.getReferenceById(forecastId));
        history.setExportFormat(format);
        history.setExportDate(LocalDateTime.now());
        exportHistoryRepository.save(history);
    }
}
