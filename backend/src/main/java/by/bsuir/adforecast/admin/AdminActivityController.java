package by.bsuir.adforecast.admin;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

@RestController
@RequestMapping("/api/admin/activity")
public class AdminActivityController {

    private final AdminActivityService adminActivityService;

    public AdminActivityController(AdminActivityService adminActivityService) {
        this.adminActivityService = adminActivityService;
    }

    /**
     * Распределение действий журнала по типам за период. Без границ — за всё время.
     * Границы приходят как ISO-8601 с часовым поясом, чтобы «день» и «период»
     * считались по календарю пользователя, а не сервера.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('admin.view')")
    public AdminActivityResponse activity(@RequestParam(required = false) String from,
                                          @RequestParam(required = false) String to) {
        OffsetDateTime start = parse(from, "from");
        OffsetDateTime end = parse(to, "to");
        if (start != null && end != null && !start.isBefore(end)) {
            throw new IllegalArgumentException("начало периода должно быть раньше окончания");
        }
        return adminActivityService.activity(start, end);
    }

    /** Периоды выбранного типа, в которых есть действия: час, день, неделя или месяц. */
    @GetMapping("/periods")
    @PreAuthorize("hasAuthority('admin.view')")
    public AdminActivityPeriodsResponse periods(@RequestParam(required = false) String unit,
                                                @RequestParam(required = false) String tz) {
        return adminActivityService.periods(unit, tz);
    }

    private static OffsetDateTime parse(String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("некорректная дата в параметре " + name);
        }
    }
}
