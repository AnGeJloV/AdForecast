package by.bsuir.adforecast.upload;

import java.time.LocalDate;
import java.util.List;

public record AnalyzeResponse(
        String format,
        String delimiter,
        String charset,
        String dateFormat,
        List<ColumnDef> columns,
        List<List<String>> previewRows,
        int rowCount,
        LocalDate periodStart,
        LocalDate periodEnd,
        List<String> warnings,
        List<String> errors) {

    public record ColumnDef(int index, String sourceName, String field, String method) {
    }
}
