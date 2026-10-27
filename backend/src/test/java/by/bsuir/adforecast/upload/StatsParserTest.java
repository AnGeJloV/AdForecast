package by.bsuir.adforecast.upload;

import by.bsuir.adforecast.domain.UploadFormat;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class StatsParserTest {

    private final StatsParser parser = new StatsParser();

    private static final String ENGLISH_CSV = """
            date,impressions,clicks,cost,revenue
            2026-08-01,1000,50,10.50,15.75
            2026-08-02,1100,55,11.00,16.00
            """;

    private static String russianCsv() {
        return "Дата;Показы;Клики;Расход;Доход\n"
                + "01.08.2026;1000;50;10,50;15,75\n"
                + "02.08.2026;2 000;60;11,00;16,25\n";
    }

    @Test
    void detectFormatRecognizesXlsxByMagicBytes() throws IOException {
        assertThat(parser.detectFormat(multipartFile(new byte[]{'P', 'K', 3, 4, 0, 0})))
                .isEqualTo(UploadFormat.XLSX);
    }

    @Test
    void detectFormatTreatsOtherBytesAsCsv() throws IOException {
        assertThat(parser.detectFormat(multipartFile("дата".getBytes(StandardCharsets.UTF_8))))
                .isEqualTo(UploadFormat.CSV);
    }

    @Test
    void detectFormatRejectsShortFile() {
        assertThatThrownBy(() -> parser.detectFormat(multipartFile(new byte[]{'P', 'K'})))
                .isInstanceOf(IOException.class);
    }

    @Test
    void analyzeMapsStandardCsvByHeaderNames() throws IOException {
        AnalyzeResponse analysis = parser.analyze(UploadFormat.CSV, utf8Stream(ENGLISH_CSV));

        assertThat(analysis.format()).isEqualTo("CSV");
        assertThat(analysis.delimiter()).isEqualTo(",");
        assertThat(analysis.charset()).isEqualTo("UTF-8");
        assertThat(analysis.dateFormat()).isEqualTo("ГГГГ-ММ-ДД");
        assertThat(analysis.errors()).isEmpty();
        assertThat(analysis.rowCount()).isEqualTo(2);
        assertThat(analysis.periodStart()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(analysis.periodEnd()).isEqualTo(LocalDate.of(2026, 8, 2));
        assertThat(analysis.columns())
                .extracting(AnalyzeResponse.ColumnDef::field, AnalyzeResponse.ColumnDef::method)
                .containsExactly(
                        tuple("date", "по названию"),
                        tuple("impressions", "по названию"),
                        tuple("clicks", "по названию"),
                        tuple("cost", "по названию"),
                        tuple("revenue", "по названию"));
    }

    @Test
    void analyzeDetectsRussianCsvWithSemicolonAndCp1251() throws IOException {
        InputStream input = new ByteArrayInputStream(russianCsv().getBytes(Charset.forName("windows-1251")));

        AnalyzeResponse analysis = parser.analyze(UploadFormat.CSV, input);

        assertThat(analysis.charset()).isEqualTo("windows-1251");
        assertThat(analysis.delimiter()).isEqualTo(";");
        assertThat(analysis.dateFormat()).isEqualTo("ДД.ММ.ГГГГ");
        assertThat(analysis.errors()).isEmpty();
        assertThat(analysis.columns())
                .extracting(AnalyzeResponse.ColumnDef::field)
                .containsExactly("date", "impressions", "clicks", "cost", "revenue");
    }

    @Test
    void analyzeStripsByteOrderMarkAndSkipsServiceRows() throws IOException {
        String csv = "Дата;Показы;Клики;Расход;Доход\n"
                + "01.08.2026;1000;50;10,50;15,75\n"
                + "02.08.2026;1100;55;11,00;16,00\n"
                + "Итого;2100;105;21,50;31,75\n";
        byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = csv.getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, bytes, 0, bom.length);
        System.arraycopy(body, 0, bytes, bom.length, body.length);

        AnalyzeResponse analysis = parser.analyze(UploadFormat.CSV, new ByteArrayInputStream(bytes));

        assertThat(analysis.charset()).isEqualTo("UTF-8");
        assertThat(analysis.columns().get(0).sourceName()).isEqualTo("Дата");
        assertThat(analysis.rowCount()).isEqualTo(2);
    }

    @Test
    void analyzeFallsBackToContentDetectionWhenHeadersUnknown() throws IOException {
        StringBuilder csv = new StringBuilder("Колонка 1,Колонка 2,Колонка 3,Колонка 4,Колонка 5\n");
        for (int i = 0; i < 12; i++) {
            int clicks = 500 + i;
            double cost = clicks * 0.5 + 0.25;
            double revenue = clicks * 0.2 + (i % 2);
            csv.append(String.format("%02d.08.2026,%d,%d,%.2f,%.2f%n",
                    i + 1, 1000 + i * 10, clicks, cost, revenue));
        }

        AnalyzeResponse analysis = parser.analyze(UploadFormat.CSV, utf8Stream(csv.toString()));

        assertThat(analysis.errors()).isEmpty();
        assertThat(analysis.columns())
                .extracting(AnalyzeResponse.ColumnDef::field, AnalyzeResponse.ColumnDef::method)
                .containsExactly(
                        tuple("date", "по содержимому"),
                        tuple("impressions", "по содержимому"),
                        tuple("clicks", "по содержимому"),
                        tuple("cost", "по содержимому"),
                        tuple("revenue", "по содержимому"));
        assertThat(analysis.warnings()).isNotEmpty();
    }

    @Test
    void analyzeReportsColumnsItCouldNotDetect() throws IOException {
        String csv = """
                дата,показы,клики
                2026-08-01,100,5
                """;

        AnalyzeResponse analysis = parser.analyze(UploadFormat.CSV, utf8Stream(csv));

        assertThat(analysis.errors()).hasSize(1);
        assertThat(analysis.errors().get(0)).contains("расход", "доход", "вручную");
    }

    @Test
    void analyzeCollectsRowErrorsWithLineNumbers() throws IOException {
        String csv = """
                date,impressions,clicks,cost,revenue
                2026-08-01,1000,50,10.50,15.75
                2026-08-01,900,45,9.00,13.00
                2026-08-03,abc,55,11,16
                2026-08-04,1200,60,-5,18
                2026-08-05,1300,65,13,19
                """;

        AnalyzeResponse analysis = parser.analyze(UploadFormat.CSV, utf8Stream(csv));

        assertThat(analysis.periodStart()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(analysis.periodEnd()).isEqualTo(LocalDate.of(2026, 8, 5));
        assertThat(analysis.errors()).contains(
                "строка 3: дата 2026-08-01 повторяется в файле",
                "строка 4: некорректное целое число abc",
                "строка 5: отрицательное значение -5");
    }

    @Test
    void analyzeRejectsEmptyCsvWithoutDataRows() {
        String csv = "date,impressions,clicks,cost,revenue\n";

        assertThatThrownBy(() -> parser.analyze(UploadFormat.CSV, utf8Stream(csv)))
                .isInstanceOf(UploadException.class)
                .hasMessageContaining("не содержит строк с показателями");
    }

    @Test
    void parseUsesExplicitMappingAndParsesSpaceThousands() throws IOException {
        ParsedRows parsed = parser.parse(UploadFormat.CSV, utf8Stream(russianCsv()),
                Map.of("date", 0, "impressions", 1, "clicks", 2, "cost", 3, "revenue", 4));

        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.rows()).hasSize(2);
        MetricRow second = parsed.rows().get(1);
        assertThat(second.date()).isEqualTo(LocalDate.of(2026, 8, 2));
        assertThat(second.impressions()).isEqualTo(2000);
        assertThat(second.cost()).isEqualByComparingTo("11.00");
        assertThat(second.revenue()).isEqualByComparingTo("16.25");
    }

    @Test
    void parseWithoutMappingDetectsColumnsByName() throws IOException {
        ParsedRows parsed = parser.parse(UploadFormat.CSV, utf8Stream(russianCsv()), null);

        assertThat(parsed.rows()).hasSize(2);
        assertThat(parsed.rows().get(0).cost()).isEqualByComparingTo("10.50");
    }

    @Test
    void parseRejectsUnmappedField() {
        assertThatThrownBy(() -> parser.parse(UploadFormat.CSV, utf8Stream(ENGLISH_CSV),
                Map.of("date", 0, "impressions", 1, "clicks", 2)))
                .isInstanceOf(UploadException.class)
                .hasMessageContaining("не сопоставлена колонка «расход»");
    }

    @Test
    void parseRejectsOutOfRangeColumnIndex() {
        assertThatThrownBy(() -> parser.parse(UploadFormat.CSV, utf8Stream(ENGLISH_CSV),
                Map.of("date", 0, "impressions", 1, "clicks", 2, "cost", 3, "revenue", 99)))
                .isInstanceOf(UploadException.class)
                .hasMessageContaining("за пределами таблицы");
    }

    @Test
    void parseReadsXlsxWithDateAndNumericCells() throws IOException {
        byte[] workbookBytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("отчет");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("date");
            header.createCell(1).setCellValue("impressions");
            header.createCell(2).setCellValue("clicks");
            header.createCell(3).setCellValue("cost");
            header.createCell(4).setCellValue("revenue");
            Row data = sheet.createRow(1);
            var dateCell = data.createCell(0);
            dateCell.setCellValue(java.time.LocalDateTime.of(2026, 8, 1, 0, 0));
            var style = workbook.createCellStyle();
            style.setDataFormat(14);
            dateCell.setCellStyle(style);
            data.createCell(1).setCellValue(1000.0);
            data.createCell(2).setCellValue(50.0);
            data.createCell(3).setCellValue(10.5);
            data.createCell(4).setCellValue(15.75);
            workbook.write(output);
            workbookBytes = output.toByteArray();
        }

        ParsedRows parsed = parser.parse(UploadFormat.XLSX, new ByteArrayInputStream(workbookBytes), null);

        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.rows()).hasSize(1);
        MetricRow row = parsed.rows().get(0);
        assertThat(row.date()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(row.impressions()).isEqualTo(1000);
        assertThat(row.clicks()).isEqualTo(50);
        assertThat(row.cost()).isEqualByComparingTo("10.5");
        assertThat(row.revenue()).isEqualByComparingTo("15.75");
    }

    private InputStream utf8Stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private org.springframework.mock.web.MockMultipartFile multipartFile(byte[] content) {
        return new org.springframework.mock.web.MockMultipartFile("file", content);
    }
}
