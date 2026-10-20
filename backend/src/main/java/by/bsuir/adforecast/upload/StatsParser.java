package by.bsuir.adforecast.upload;

import by.bsuir.adforecast.domain.UploadFormat;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class StatsParser {

    private static final List<String> REQUIRED_COLUMNS = List.of("date", "impressions", "clicks", "cost", "revenue");
    private static final DateTimeFormatter ALT_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final int MAX_ERRORS = 20;
    private static final int MAX_ROWS = 100_000;

    public UploadFormat detectFormat(MultipartFile file) throws IOException {
        byte[] head = new byte[4];
        try (InputStream input = file.getInputStream()) {
            int read = input.read(head);
            if (read < 4) {
                throw new IOException("файл слишком мал");
            }
        }
        if (head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4) {
            return UploadFormat.XLSX;
        }
        return UploadFormat.CSV;
    }

    public ParsedRows parse(UploadFormat format, InputStream input) throws IOException {
        return format == UploadFormat.XLSX ? parseXlsx(input) : parseCsv(input);
    }

    private ParsedRows parseCsv(InputStream input) throws IOException {
        List<MetricRow> rows = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Set<LocalDate> dates = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return new ParsedRows(List.of(), List.of("файл пуст"));
            }
            headerLine = stripBom(headerLine);
            Map<String, Integer> columns = mapColumns(headerLine.split(","));
            String missing = missingColumn(columns);
            if (missing != null) {
                return new ParsedRows(List.of(), List.of("отсутствует колонка " + missing));
            }
            int lineNumber = 1;
            String line;
            while ((line = reader.readLine()) != null && rows.size() < MAX_ROWS) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                addRow(rows, errors, dates, line.split(","), columns, "строка " + lineNumber);
            }
        }
        return new ParsedRows(rows, errors);
    }

    private ParsedRows parseXlsx(InputStream input) throws IOException {
        List<MetricRow> rows = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Set<LocalDate> dates = new HashSet<>();
        try (XSSFWorkbook workbook = new XSSFWorkbook(input)) {
            Sheet sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(0);
            if (header == null || header.getLastCellNum() <= 0) {
                return new ParsedRows(List.of(), List.of("файл пуст"));
            }
            Map<String, Integer> columns = mapColumns(readHeader(header));
            String missing = missingColumn(columns);
            if (missing != null) {
                return new ParsedRows(List.of(), List.of("отсутствует колонка " + missing));
            }
            for (Row row : sheet) {
                if (row.getRowNum() == 0 || rows.size() >= MAX_ROWS) {
                    continue;
                }
                addRow(rows, errors, dates, readCells(row, columns.size()), columns, "строка " + (row.getRowNum() + 1));
            }
        }
        return new ParsedRows(rows, errors);
    }

    private void addRow(List<MetricRow> rows, List<String> errors, Set<LocalDate> dates,
                        String[] values, Map<String, Integer> columns, String label) {
        String[] cells = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            cells[i] = values[i].trim();
        }
        LocalDate date = parseDate(cell(cells, columns.get("date")), label, errors);
        Integer impressions = parseInt(cell(cells, columns.get("impressions")), label, errors);
        Integer clicks = parseInt(cell(cells, columns.get("clicks")), label, errors);
        BigDecimal cost = parseDecimal(cell(cells, columns.get("cost")), label, errors);
        BigDecimal revenue = parseDecimal(cell(cells, columns.get("revenue")), label, errors);
        if (date == null || impressions == null || clicks == null || cost == null || revenue == null) {
            return;
        }
        if (!dates.add(date)) {
            fail(errors, label, "дата " + date + " повторяется в файле");
            return;
        }
        rows.add(new MetricRow(date, impressions, clicks, cost, revenue));
    }

    private Map<String, Integer> mapColumns(String[] names) {
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < names.length; i++) {
            columns.put(names[i].trim().toLowerCase(Locale.ROOT), i);
        }
        return columns;
    }

    private String[] readHeader(Row header) {
        String[] names = new String[header.getLastCellNum()];
        for (int i = 0; i < names.length; i++) {
            Cell cell = header.getCell(i);
            names[i] = cell == null ? "" : cell.toString().trim();
        }
        return names;
    }

    private String[] readCells(Row row, int width) {
        String[] values = new String[width];
        for (int i = 0; i < width; i++) {
            values[i] = readCell(row.getCell(i));
        }
        return values;
    }

    private String readCell(Cell cell) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate().toString();
        }
        return cell.toString().trim();
    }

    private String missingColumn(Map<String, Integer> columns) {
        for (String name : REQUIRED_COLUMNS) {
            if (!columns.containsKey(name)) {
                return name;
            }
        }
        return null;
    }

    private String cell(String[] cells, Integer index) {
        if (index == null || index >= cells.length) {
            return "";
        }
        return cells[index];
    }

    private LocalDate parseDate(String value, String label, List<String> errors) {
        if (value.isEmpty()) {
            fail(errors, label, "пустая дата");
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDate.parse(value, ALT_DATE);
            } catch (DateTimeParseException exception) {
                fail(errors, label, "некорректная дата " + value);
                return null;
            }
        }
    }

    private Integer parseInt(String value, String label, List<String> errors) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) {
                fail(errors, label, "отрицательное значение " + value);
                return null;
            }
            return parsed;
        } catch (NumberFormatException exception) {
            fail(errors, label, "некорректное целое число " + value);
            return null;
        }
    }

    private BigDecimal parseDecimal(String value, String label, List<String> errors) {
        try {
            BigDecimal parsed = new BigDecimal(value.replace(",", "."));
            if (parsed.signum() < 0) {
                fail(errors, label, "отрицательное значение " + value);
                return null;
            }
            return parsed;
        } catch (NumberFormatException exception) {
            fail(errors, label, "некорректное число " + value);
            return null;
        }
    }

    private void fail(List<String> errors, String label, String reason) {
        if (errors.size() < MAX_ERRORS) {
            errors.add(label + ": " + reason);
        } else if (errors.size() == MAX_ERRORS) {
            errors.add("превышен лимит сообщений об ошибках");
        }
    }

    private String stripBom(String line) {
        return line.startsWith("\ufeff") ? line.substring(1) : line;
    }
}
