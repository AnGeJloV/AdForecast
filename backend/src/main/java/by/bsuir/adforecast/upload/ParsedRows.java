package by.bsuir.adforecast.upload;

import java.util.List;

public record ParsedRows(List<MetricRow> rows, List<String> errors) {
}
