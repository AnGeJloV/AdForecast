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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class StatsParser {

    private static final List<String> FIELDS = List.of("date", "impressions", "clicks", "cost", "revenue");
    private static final List<String> FIELD_TITLES = List.of("дата", "показы", "клики", "расход", "доход");
    private static final Map<String, List<String>> SYNONYMS = Map.of(
            "date", List.of("date", "day", "дата", "день"),
            "impressions", List.of("impressions", "views", "показ", "показы", "показов", "просмотры"),
            "clicks", List.of("clicks", "клик", "клики", "кликов", "переходы"),
            "cost", List.of("cost", "spend", "spent", "расход", "расходы", "затраты", "себестоимость", "стоимость", "сумма"),
            "revenue", List.of("revenue", "income", "earnings", "доход", "выручка")
    );
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            strict("uuuu-M-d"),
            strict("d.M.uuuu"),
            strict("d/M/uuuu"),
            strict("d-M-uuuu"),
            strict("uuuu/M/d"),
            strict("M/d/uuuu")
    );

    private static DateTimeFormatter strict(String pattern) {
        return DateTimeFormatter.ofPattern(pattern).withResolverStyle(java.time.format.ResolverStyle.STRICT);
    }

    private static final char[] DELIMITER_CANDIDATES = {',', ';', '\t', '|'};
    private static final int MAX_ROWS = 100_000;
    private static final int MAX_ERRORS = 20;
    private static final int SAMPLE_SIZE = 200;
    private static final int PREVIEW_ROWS = 5;
    private static final int MIN_CORRELATION_PAIRS = 10;

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

    public AnalyzeResponse analyze(UploadFormat format, InputStream input) throws IOException {
        Table table = readTable(format, input);
        Detection detection = detectColumns(table);
        List<String> warnings = new ArrayList<>(detection.notes());
        List<String> errors = new ArrayList<>();
        String dateFormat = null;
        LocalDate periodStart = null;
        LocalDate periodEnd = null;
        if (detection.mapping().size() < FIELDS.size()) {
            List<String> missing = new ArrayList<>();
            for (int i = 0; i < FIELDS.size(); i++) {
                if (!detection.mapping().containsKey(FIELDS.get(i))) {
                    missing.add(FIELD_TITLES.get(i));
                }
            }
            errors.add("не удалось автоматически определить колонки: " + String.join(", ", missing)
                    + " — сопоставьте колонки вручную");
        } else {
            DateStyle dateStyle = detectDateStyle(columnSamples(table, detection.mapping().get("date")));
            if (dateStyle == null) {
                errors.add("не удалось определить формат даты в колонке «дата»");
            } else {
                dateFormat = dateStyle.description();
                ParsedRows parsed = parseRows(table, detection.mapping(), dateStyle);
                errors.addAll(parsed.errors());
                if (!parsed.rows().isEmpty()) {
                    periodStart = parsed.rows().stream().map(MetricRow::date).min(Comparator.naturalOrder()).orElseThrow();
                    periodEnd = parsed.rows().stream().map(MetricRow::date).max(Comparator.naturalOrder()).orElseThrow();
                }
            }
        }
        return new AnalyzeResponse(
                format.name(), table.delimiter(), table.charset().name(), dateFormat,
                detection.columns(table.header()), preview(table), table.rows().size(),
                periodStart, periodEnd, warnings, errors);
    }

    public ParsedRows parse(UploadFormat format, InputStream input, Map<String, Integer> mapping) throws IOException {
        Table table = readTable(format, input);
        Map<String, Integer> columns = mapping == null ? detectColumns(table).mapping() : mapping;
        List<String> problems = new ArrayList<>();
        for (String field : FIELDS) {
            Integer index = columns.get(field);
            if (index == null) {
                problems.add("не сопоставлена колонка «" + fieldTitle(field) + "»");
            } else if (index < 0 || index >= table.header().size()) {
                problems.add("колонка «" + fieldTitle(field) + "» указана за пределами таблицы");
            }
        }
        if (!problems.isEmpty()) {
            throw new UploadException(problems);
        }
        DateStyle dateStyle = detectDateStyle(columnSamples(table, columns.get("date")));
        if (dateStyle == null) {
            throw new UploadException(List.of("не удалось определить формат даты в колонке «дата»"));
        }
        return parseRows(table, columns, dateStyle);
    }

    private String fieldTitle(String field) {
        return FIELD_TITLES.get(FIELDS.indexOf(field));
    }


    private record Table(String delimiter, Charset charset, List<String> header, List<LabeledRow> rows) {
    }

    private record LabeledRow(List<String> cells, int number) {
    }

    private Table readTable(UploadFormat format, InputStream input) throws IOException {
        return format == UploadFormat.XLSX ? readXlsx(input) : readCsv(input);
    }

    private Table readCsv(InputStream input) throws IOException {
        byte[] bytes = input.readAllBytes();
        Charset charset = detectCharset(bytes);
        List<String> lines = new ArrayList<>();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(new ByteArrayInputStream(bytes), charset))) {
            String line;
            while ((line = reader.readLine()) != null && lines.size() <= MAX_ROWS + 10) {
                if (!line.isBlank()) {
                    lines.add(line);
                }
            }
        }
        if (lines.isEmpty()) {
            throw new UploadException(List.of("файл пуст"));
        }
        lines.set(0, stripBom(lines.get(0)));
        char delimiter = detectDelimiter(lines);
        List<LabeledRow> all = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            all.add(new LabeledRow(splitLine(lines.get(i), delimiter), i + 1));
        }
        return buildTable(String.valueOf(delimiter), charset, all);
    }

    private Table readXlsx(InputStream input) throws IOException {
        List<LabeledRow> all = new ArrayList<>();
        try (XSSFWorkbook workbook = new XSSFWorkbook(input)) {
            Sheet sheet = workbook.getSheetAt(0);
            for (Row row : sheet) {
                if (row.getRowNum() > MAX_ROWS) {
                    break;
                }
                int width = Math.max(row.getLastCellNum(), 1);
                List<String> cells = new ArrayList<>(width);
                for (int i = 0; i < width; i++) {
                    cells.add(readCell(row.getCell(i)));
                }
                all.add(new LabeledRow(cells, row.getRowNum() + 1));
            }
        }
        return buildTable(null, StandardCharsets.UTF_8, all);
    }

    private Table buildTable(String delimiter, Charset charset, List<LabeledRow> all) {
        if (all.isEmpty()) {
            throw new UploadException(List.of("файл пуст"));
        }
        List<String> header = new ArrayList<>();
        for (String cell : all.get(0).cells()) {
            header.add(cell == null ? "" : cell.trim());
        }
        List<LabeledRow> rows = new ArrayList<>();
        for (int i = 1; i < all.size() && rows.size() < MAX_ROWS; i++) {
            LabeledRow row = all.get(i);
            if (isServiceRow(row.cells()) || isBlankRow(row.cells())) {
                continue;
            }
            rows.add(row);
        }
        if (rows.isEmpty()) {
            throw new UploadException(List.of("файл не содержит строк с показателями"));
        }
        return new Table(delimiter, charset, header, rows);
    }

    private Charset detectCharset(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes));
            return StandardCharsets.UTF_8;
        } catch (java.nio.charset.CharacterCodingException exception) {
            return Charset.forName("windows-1251");
        }
    }

    private char detectDelimiter(List<String> lines) {
        int limit = Math.min(lines.size(), 50);
        char best = ',';
        double bestScore = -1;
        for (char candidate : DELIMITER_CANDIDATES) {
            double total = 0;
            int linesWith = 0;
            for (int i = 0; i < limit; i++) {
                int count = countOutsideQuotes(lines.get(i), candidate);
                if (count > 0) {
                    total += count;
                    linesWith++;
                }
            }
            if (linesWith == 0) {
                continue;
            }
            double score = total / linesWith + linesWith * 0.001;
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private int countOutsideQuotes(String line, char target) {
        boolean quoted = false;
        int count = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (c == target && !quoted) {
                count++;
            }
        }
        return count;
    }

    private List<String> splitLine(String line, char delimiter) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == delimiter) {
                cells.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        cells.add(current.toString().trim());
        return cells;
    }

    private String readCell(Cell cell) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate().toString();
        }
        String value = cell.toString().trim();
        return value.matches("\\d+\\.0+") ? value.substring(0, value.length() - 2) : value;
    }

    private boolean isServiceRow(List<String> cells) {
        if (cells.isEmpty() || cells.get(0) == null) {
            return false;
        }
        String first = cells.get(0).toLowerCase(Locale.ROOT).replace("ё", "е").trim();
        first = first.replaceFirst("[\\s:.,]+$", "");
        return first.startsWith("итог") || first.equals("всего") || first.startsWith("всего")
                || first.startsWith("total") || first.equals("sum") || first.equals("сумма");
    }

    private boolean isBlankRow(List<String> cells) {
        return cells.stream().allMatch(cell -> cell == null || cell.trim().isEmpty());
    }

    private String stripBom(String line) {
        return line.startsWith("\uFEFF") ? line.substring(1) : line;
    }


    private record Detection(Map<String, Integer> mapping, Map<String, String> methods, List<String> notes) {

        private List<AnalyzeResponse.ColumnDef> columns(List<String> header) {
            Map<Integer, String> reverse = new HashMap<>();
            mapping.forEach((field, index) -> reverse.put(index, field));
            List<AnalyzeResponse.ColumnDef> result = new ArrayList<>();
            for (int i = 0; i < header.size(); i++) {
                String field = reverse.get(i);
                result.add(new AnalyzeResponse.ColumnDef(
                        i, header.get(i), field,
                        field == null ? null : methods.getOrDefault(field, "по названию")));
            }
            return result;
        }
    }

    private Detection detectColumns(Table table) {
        List<String> header = table.header();
        List<ColumnProfile> profiles = profiles(table);
        Map<String, Integer> mapping = new LinkedHashMap<>();
        Map<String, String> methods = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        Set<Integer> taken = new HashSet<>();
        Set<Integer> excluded = new HashSet<>();

        for (int i = 0; i < header.size(); i++) {
            String name = normalize(header.get(i));
            if (name.isEmpty()) {
                continue;
            }
            if (isDerived(name)) {
                excluded.add(i);
                notes.add("колонка «" + header.get(i) + "» пропущена как производный показатель (CTR/CPC/CPM)");
                continue;
            }
            long matchedFields = FIELDS.stream()
                    .filter(field -> matchesSynonym(name, field) || matchesToken(name, field))
                    .count();
            if (matchedFields >= 2) {
                excluded.add(i);
                notes.add("колонка «" + header.get(i) + "» пропущена как производный показатель (CPC/CPM и подобные)");
            }
        }

        for (String field : FIELDS) {
            Integer exact = null;
            Integer weak = null;
            for (int i = 0; i < header.size(); i++) {
                if (taken.contains(i) || excluded.contains(i)) {
                    continue;
                }
                String name = normalize(header.get(i));
                if (name.isEmpty()) {
                    continue;
                }
                if (matchesSynonym(name, field)) {
                    exact = i;
                    break;
                }
                if (weak == null && matchesToken(name, field)) {
                    weak = i;
                }
            }
            Integer chosen = exact != null ? exact : weak;
            if (chosen != null) {
                mapping.put(field, chosen);
                taken.add(chosen);
                methods.put(field, "по названию");
            }
        }

        detectDateColumn(profiles, mapping, methods, taken);
        detectIntegerColumns(table, profiles, mapping, methods, taken, notes);
        detectMoneyColumns(table, profiles, mapping, methods, taken, notes);

        return new Detection(mapping, methods, notes);
    }

    private void detectDateColumn(List<ColumnProfile> profiles,
                                  Map<String, Integer> mapping, Map<String, String> methods, Set<Integer> taken) {
        if (mapping.containsKey("date")) {
            return;
        }
        profiles.stream()
                .filter(profile -> !taken.contains(profile.index()) && profile.dateLike() >= 0.5)
                .max(Comparator.comparingDouble(ColumnProfile::dateLike))
                .ifPresent(profile -> assign(mapping, methods, taken, "date", profile));
    }

    private void detectIntegerColumns(Table table, List<ColumnProfile> profiles,
                                       Map<String, Integer> mapping, Map<String, String> methods,
                                       Set<Integer> taken, List<String> notes) {
        List<ColumnProfile> candidates = untakenIntegerCandidates(profiles, taken);
        if (!mapping.containsKey("impressions")) {
            if (!candidates.isEmpty()) {
                assign(mapping, methods, taken, "impressions", candidates.get(0));
                notes.add("колонка «показы» определена по содержимому ("
                        + headerName(table, mapping.get("impressions")) + ")");
            }
        }
        if (!mapping.containsKey("clicks")) {
            candidates = untakenIntegerCandidates(profiles, taken);
            if (!candidates.isEmpty()) {
                assign(mapping, methods, taken, "clicks", candidates.get(0));
                notes.add("колонка «клики» определена по содержимому ("
                        + headerName(table, mapping.get("clicks")) + ")");
            }
        }
        ColumnProfile impressions = profileByIndex(profiles, mapping.get("impressions"));
        ColumnProfile clicks = profileByIndex(profiles, mapping.get("clicks"));
        if (impressions != null && clicks != null && impressions.mean() < clicks.mean()) {
            notes.add("среднее значение в колонке показов меньше, чем в колонке кликов — возможно, колонки перепутаны");
        }
    }

    private void detectMoneyColumns(Table table, List<ColumnProfile> profiles,
                                     Map<String, Integer> mapping, Map<String, String> methods,
                                     Set<Integer> taken, List<String> notes) {
        List<ColumnProfile> candidates = profiles.stream()
                .filter(profile -> !taken.contains(profile.index()) && profile.decimalLike() >= 0.5)
                .toList();
        if (candidates.isEmpty()) {
            return;
        }
        double[] trafficColumn = numericColumn(table, mapping.get("clicks"));
        if (trafficColumn.length == 0) {
            trafficColumn = numericColumn(table, mapping.get("impressions"));
        }
        final double[] clicks = trafficColumn;
        List<ColumnProfile> ranked = new ArrayList<>(candidates);
        if (clicks.length > 0) {
            ranked.sort(Comparator.comparingDouble(
                    (ColumnProfile profile) -> correlation(clicks, numericColumn(table, profile.index()))).reversed());
        } else {
            ranked.sort(Comparator.comparingDouble(ColumnProfile::zeroFraction));
        }
        if (!mapping.containsKey("cost")) {
            assign(mapping, methods, taken, "cost", ranked.get(0));
            notes.add("колонка «расход» определена по содержимому ("
                    + headerName(table, mapping.get("cost")) + ")");
            ranked = ranked.stream().filter(profile -> !taken.contains(profile.index())).toList();
            if (ranked.isEmpty()) {
                return;
            }
        }
        if (!mapping.containsKey("revenue")) {
            List<ColumnProfile> remaining = new ArrayList<>(ranked);
            remaining.sort(Comparator.comparingDouble(ColumnProfile::zeroFraction).reversed());
            assign(mapping, methods, taken, "revenue", remaining.get(0));
            notes.add("колонка «доход» определена по содержимому ("
                    + headerName(table, mapping.get("revenue")) + ")");
        }
    }

    private List<ColumnProfile> untakenIntegerCandidates(List<ColumnProfile> profiles, Set<Integer> taken) {
        return profiles.stream()
                .filter(profile -> !taken.contains(profile.index()) && profile.intLike() >= 0.7)
                .sorted(Comparator.comparingDouble(ColumnProfile::mean).reversed())
                .toList();
    }

    private void assign(Map<String, Integer> mapping, Map<String, String> methods, Set<Integer> taken,
                        String field, ColumnProfile profile) {
        mapping.put(field, profile.index());
        methods.put(field, "по содержимому");
        taken.add(profile.index());
    }

    private boolean matchesSynonym(String normalizedName, String field) {
        return SYNONYMS.get(field).stream().map(this::normalize).anyMatch(normalizedName::equals);
    }

    private boolean matchesToken(String normalizedName, String field) {
        Set<String> tokens = Set.of(normalizedName.split("\\s+"));
        return SYNONYMS.get(field).stream()
                .map(this::normalize)
                .anyMatch(synonym -> tokens.stream().anyMatch(token -> tokenMatches(token, synonym)));
    }

    private boolean tokenMatches(String token, String synonym) {
        return token.equals(synonym)
                || (synonym.length() >= 4 && token.startsWith(synonym))
                || (token.length() >= 4 && synonym.startsWith(token));
    }

    private boolean isDerived(String normalizedName) {
        Set<String> tokens = Set.of(normalizedName.split("\\s+"));
        return tokens.stream().anyMatch(token -> token.equals("ctr") || token.equals("cpc") || token.equals("cpm"));
    }

    private String normalize(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replace("ё", "е")
                .replaceAll("[\\p{Punct}\\u00AB\\u00BB«»„“”]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private String headerName(Table table, Integer index) {
        return index == null ? "" : table.header().get(index);
    }


    private record ColumnProfile(int index, double dateLike, double intLike, double decimalLike,
                                 double mean, double zeroFraction) {
    }

    private List<ColumnProfile> profiles(Table table) {
        List<ColumnProfile> result = new ArrayList<>();
        for (int column = 0; column < table.header().size(); column++) {
            List<String> samples = columnSamples(table, column);
            int total = samples.size();
            if (total == 0) {
                result.add(new ColumnProfile(column, 0, 0, 0, 0, 0));
                continue;
            }
            int dates = 0;
            int integers = 0;
            int numbers = 0;
            int zeros = 0;
            double sum = 0;
            for (String sample : samples) {
                if (!sample.isEmpty() && parseDateLenient(sample) != null) {
                    dates++;
                }
                if (!sample.isEmpty() && sample.matches("\\d+")) {
                    integers++;
                }
                BigDecimal number = parseNumber(sample);
                if (number != null) {
                    numbers++;
                    if (number.signum() == 0) {
                        zeros++;
                    }
                    sum += number.doubleValue();
                }
            }
            double parsed = Math.max(integers, numbers);
            result.add(new ColumnProfile(
                    column,
                    (double) dates / total,
                    (double) integers / total,
                    (double) numbers / total,
                    parsed == 0 ? 0 : sum / parsed,
                    parsed == 0 ? 0 : (double) zeros / parsed));
        }
        return result;
    }

    private List<String> columnSamples(Table table, Integer column) {
        if (column == null || column < 0) {
            return List.of();
        }
        List<String> samples = new ArrayList<>();
        for (LabeledRow row : table.rows()) {
            if (samples.size() >= SAMPLE_SIZE) {
                break;
            }
            if (column < row.cells().size()) {
                String cell = row.cells().get(column);
                samples.add(cell == null ? "" : cell.trim());
            }
        }
        return samples;
    }

    private double[] numericColumn(Table table, Integer column) {
        List<String> samples = columnSamples(table, column);
        double[] values = new double[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            BigDecimal number = parseNumber(samples.get(i));
            values[i] = number == null ? Double.NaN : number.doubleValue();
        }
        return values;
    }

    private double correlation(double[] first, double[] second) {
        if (first.length != second.length) {
            int size = Math.min(first.length, second.length);
            first = Arrays.copyOf(first, size);
            second = Arrays.copyOf(second, size);
        }
        int pairs = 0;
        double sumFirst = 0;
        double sumSecond = 0;
        for (int i = 0; i < first.length; i++) {
            if (Double.isNaN(first[i]) || Double.isNaN(second[i])) {
                continue;
            }
            pairs++;
            sumFirst += first[i];
            sumSecond += second[i];
        }
        if (pairs < MIN_CORRELATION_PAIRS) {
            return 0;
        }
        double meanFirst = sumFirst / pairs;
        double meanSecond = sumSecond / pairs;
        double covariance = 0;
        double varianceFirst = 0;
        double varianceSecond = 0;
        for (int i = 0; i < first.length; i++) {
            if (Double.isNaN(first[i]) || Double.isNaN(second[i])) {
                continue;
            }
            double a = first[i] - meanFirst;
            double b = second[i] - meanSecond;
            covariance += a * b;
            varianceFirst += a * a;
            varianceSecond += b * b;
        }
        if (varianceFirst == 0 || varianceSecond == 0) {
            return 0;
        }
        return covariance / Math.sqrt(varianceFirst * varianceSecond);
    }

    private ColumnProfile profileByIndex(List<ColumnProfile> profiles, Integer index) {
        if (index == null) {
            return null;
        }
        return profiles.stream()
                .filter(profile -> profile.index() == index)
                .findFirst()
                .orElse(null);
    }


    private record DateStyle(DateTimeFormatter primary, String description) {
    }

    private DateStyle detectDateStyle(List<String> samples) {
        List<String> nonEmpty = samples.stream().filter(sample -> !sample.isEmpty()).toList();
        if (nonEmpty.isEmpty()) {
            return null;
        }
        DateStyle best = null;
        int bestCount = 0;
        for (int i = 0; i < DATE_FORMATS.size(); i++) {
            int count = 0;
            for (String sample : nonEmpty) {
                if (parseDateStrict(sample, DATE_FORMATS.get(i)) != null) {
                    count++;
                }
            }
            if (count > bestCount) {
                bestCount = count;
                best = new DateStyle(DATE_FORMATS.get(i), describeDateFormat(i));
            }
        }
        return best != null && bestCount >= nonEmpty.size() * 0.8 ? best : null;
    }

    private String describeDateFormat(int index) {
        return switch (index) {
            case 0 -> "ГГГГ-ММ-ДД";
            case 1 -> "ДД.ММ.ГГГГ";
            case 2 -> "ДД/ММ/ГГГГ";
            case 3 -> "ДД-ММ-ГГГГ";
            case 4 -> "ГГГГ/ММ/ДД";
            default -> "ММ/ДД/ГГГГ";
        };
    }

    private LocalDate parseDateLenient(String value) {
        for (DateTimeFormatter format : DATE_FORMATS) {
            LocalDate parsed = parseDateStrict(value, format);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private LocalDate parseDateStrict(String value, DateTimeFormatter format) {
        try {
            return LocalDate.parse(value.trim(), format);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private BigDecimal parseNumber(String raw) {
        String value = stripNoise(raw);
        if (value.isEmpty()) {
            return null;
        }
        boolean hasDot = value.indexOf('.') >= 0;
        boolean hasComma = value.indexOf(',') >= 0;
        try {
            if (hasDot && hasComma) {
                if (value.lastIndexOf(',') > value.lastIndexOf('.')) {
                    return new BigDecimal(value.replace(".", "").replace(',', '.'));
                }
                return new BigDecimal(value.replace(",", ""));
            }
            if (hasComma) {
                if (value.matches(".*,\\d{1,2}$")) {
                    return new BigDecimal(value.replace(',', '.'));
                }
                return new BigDecimal(value.replace(",", ""));
            }
            if (value.indexOf('.') != value.lastIndexOf('.')) {
                return new BigDecimal(value.replace(".", ""));
            }
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Integer parseIntegerValue(String raw) {
        String value = stripNoise(raw);
        if (value.isEmpty()) {
            return null;
        }
        if (!value.matches("\\d+([.,]0+)?")) {
            return null;
        }
        try {
            return Integer.parseInt(value.replace(",", ".").replaceAll("\\.0+$", ""));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String stripNoise(String raw) {
        return raw.replace("\u00A0", "").replace("\u2009", "").replace(" ", "")
                .replaceAll("[^0-9.,\\-]", "");
    }


    private ParsedRows parseRows(Table table, Map<String, Integer> mapping, DateStyle dateStyle) {
        List<MetricRow> rows = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Set<LocalDate> dates = new HashSet<>();
        for (LabeledRow labeled : table.rows()) {
            List<String> cells = labeled.cells();
            String label = "строка " + labeled.number();
            LocalDate date = parseRowDate(cell(cells, mapping.get("date")), dateStyle, label, errors);
            Integer impressions = parseRowInt(cell(cells, mapping.get("impressions")), label, errors);
            Integer clicks = parseRowInt(cell(cells, mapping.get("clicks")), label, errors);
            BigDecimal cost = parseRowDecimal(cell(cells, mapping.get("cost")), label, errors);
            BigDecimal revenue = parseRowDecimal(cell(cells, mapping.get("revenue")), label, errors);
            if (date == null || impressions == null || clicks == null || cost == null || revenue == null) {
                continue;
            }
            if (!dates.add(date)) {
                fail(errors, label, "дата " + date + " повторяется в файле");
                continue;
            }
            rows.add(new MetricRow(date, impressions, clicks, cost, revenue));
        }
        if (rows.isEmpty() && errors.isEmpty()) {
            errors.add("файл не содержит строк с показателями");
        }
        return new ParsedRows(rows, errors);
    }

    private LocalDate parseRowDate(String value, DateStyle style, String label, List<String> errors) {
        if (value.isEmpty()) {
            fail(errors, label, "пустая дата");
            return null;
        }
        LocalDate parsed = parseDateStrict(value, style.primary());
        if (parsed == null) {
            parsed = parseDateLenient(value);
        }
        if (parsed == null) {
            fail(errors, label, "некорректная дата " + value);
        }
        return parsed;
    }

    private Integer parseRowInt(String value, String label, List<String> errors) {
        Integer parsed = parseIntegerValue(value);
        if (parsed == null) {
            fail(errors, label, "некорректное целое число " + (value.isEmpty() ? "(пусто)" : value));
            return null;
        }
        if (parsed < 0) {
            fail(errors, label, "отрицательное значение " + value);
            return null;
        }
        return parsed;
    }

    private BigDecimal parseRowDecimal(String value, String label, List<String> errors) {
        BigDecimal parsed = parseNumber(value);
        if (parsed == null) {
            fail(errors, label, "некорректное число " + (value.isEmpty() ? "(пусто)" : value));
            return null;
        }
        if (parsed.signum() < 0) {
            fail(errors, label, "отрицательное значение " + value);
            return null;
        }
        return parsed;
    }

    private String cell(List<String> cells, Integer index) {
        if (index == null || index >= cells.size()) {
            return "";
        }
        String value = cells.get(index);
        return value == null ? "" : value.trim();
    }

    private List<List<String>> preview(Table table) {
        return table.rows().stream()
                .limit(PREVIEW_ROWS)
                .map(row -> row.cells().stream()
                        .map(cell -> cell == null ? "" : cell)
                        .toList())
                .toList();
    }

    private void fail(List<String> errors, String label, String reason) {
        if (errors.size() < MAX_ERRORS) {
            errors.add(label + ": " + reason);
        } else if (errors.size() == MAX_ERRORS) {
            errors.add("превышен лимит сообщений об ошибках");
        }
    }
}
