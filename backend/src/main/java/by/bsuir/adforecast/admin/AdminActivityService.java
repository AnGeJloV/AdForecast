package by.bsuir.adforecast.admin;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.time.temporal.ChronoUnit;

@Service
public class AdminActivityService {

    /** Действия журнала сгруппированы по смыслу так же, как их читает администратор. */
    private static final Map<String, String> LABELS = Map.of(
            "campaigns", "кампании",
            "forecasts", "прогнозы",
            "data", "данные и отчёты",
            "models", "модели и обучение",
            "users", "входы и учётные записи",
            "other", "прочее");

    private static final Set<String> UNITS = Set.of("hour", "day", "week", "month");

    private static final String ACTIVITY_SQL = """
            select case
                       when a.action like '%кампани%' then 'campaigns'
                       when a.action like '%прогноз%' then 'forecasts'
                       when a.action like '%загрузка%' or a.action like '%отчёт%' or a.action like '%отчет%'
                            or a.action like '%резервн%' or a.action like '%копи%' then 'data'
                       when a.action like '%обучени%' or a.action like '%модел%' then 'models'
                       when a.action like '%вход%' or a.action like '%выход%' or a.action like '%регистрац%'
                            or a.action like '%пользовател%' or a.action like '%блокировк%' then 'users'
                       else 'other'
                   end as category,
                   count(*) as total
            from audit_actions a
            where (cast(:from as timestamp) is null or a.action_time >= cast(:from as timestamp))
              and (cast(:to as timestamp) is null or a.action_time < cast(:to as timestamp))
            group by 1
            order by 2 desc, 1
            """;

    /**
     * Периоды выбранного типа за текущий календарный контейнер пользователя: час —
     * за сегодняшний день, день — за текущий месяц, неделя — за текущий квартал,
     * месяц — за текущий год. Границы считаются по часовому поясу клиента: журнал
     * хранит местное время приложения, поэтому сначала оно переводится в момент
     * времени, затем в календарь пользователя.
     */
    private static final String PERIODS_SQL = """
            select (date_trunc(cast(:unit as text),
                       (a.action_time at time zone cast(:appZone as text))
                       at time zone cast(:zone as text))
                   at time zone cast(:zone as text)) as bucket_start,
                   count(*) as total
            from audit_actions a
            where a.action_time >= cast(:from as timestamp)
              and a.action_time < cast(:to as timestamp)
            group by 1
            order by 1 desc
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public AdminActivityService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public AdminActivityResponse activity(OffsetDateTime from, OffsetDateTime to) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("from", local(from), Types.TIMESTAMP)
                .addValue("to", local(to), Types.TIMESTAMP);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(ACTIVITY_SQL, params);

        long total = 0;
        List<AdminActivityResponse.Group> groups = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            String category = String.valueOf(row.get("category"));
            long count = ((Number) row.get("total")).longValue();
            total += count;
            groups.add(new AdminActivityResponse.Group(category,
                    LABELS.getOrDefault(category, LABELS.get("other")), count));
        }
        return new AdminActivityResponse(total, groups);
    }

    @Transactional(readOnly = true)
    public AdminActivityPeriodsResponse periods(String unit, String timeZone) {
        String normalized = unit == null || unit.isBlank()
                ? "day" : unit.trim().toLowerCase(Locale.ROOT);
        if (!UNITS.contains(normalized)) {
            throw new IllegalArgumentException("неизвестный период: " + unit);
        }
        ZoneId zone = zone(timeZone);
        ZonedDateTime localNow = OffsetDateTime.now().atZoneSameInstant(zone);
        ZonedDateTime start = windowStart(localNow, normalized);
        ZonedDateTime end = switch (normalized) {
            case "hour" -> start.plusDays(1);
            case "day" -> start.plusMonths(1);
            case "week" -> start.plusMonths(3);
            default -> start.plusYears(1);
        };
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("unit", normalized)
                .addValue("appZone", ZoneId.systemDefault().getId())
                .addValue("zone", zone.getId())
                .addValue("from", local(start.toOffsetDateTime()), Types.TIMESTAMP)
                .addValue("to", local(end.toOffsetDateTime()), Types.TIMESTAMP);

        List<AdminActivityPeriodsResponse.Period> periods = jdbcTemplate.query(PERIODS_SQL, params,
                (rs, rowNum) -> {
                    OffsetDateTime bucket = rs.getObject("bucket_start", OffsetDateTime.class)
                            .atZoneSameInstant(zone).toOffsetDateTime();
                    return new AdminActivityPeriodsResponse.Period(bucket, end(bucket, normalized, zone),
                            ((Number) rs.getObject("total")).longValue());
                });
        return new AdminActivityPeriodsResponse(periods);
    }

    /** Начало календарного контейнера в поясе пользователя: день, месяц, квартал, год. */
    private static ZonedDateTime windowStart(ZonedDateTime now, String unit) {
        return switch (unit) {
            case "hour" -> now.toLocalDate().atStartOfDay(now.getZone());
            case "day" -> now.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS);
            case "week" -> {
                int quarterMonth = ((now.getMonthValue() - 1) / 3) * 3 + 1;
                yield now.withMonth(quarterMonth).withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS);
            }
            default -> now.withMonth(1).withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS);
        };
    }

    /** Границы периода приходят с часовым поясом клиента, журнал пишет местное время приложения. */
    private static LocalDateTime local(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }

    private static ZoneId zone(String timeZone) {
        if (timeZone == null || timeZone.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(timeZone);
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("неизвестный часовой пояс: " + timeZone);
        }
    }

    /** Конец календарного периода в поясе пользователя: сутки и месяцы разной длины. */
    private static OffsetDateTime end(OffsetDateTime start, String unit, ZoneId zone) {
        ZonedDateTime local = start.atZoneSameInstant(zone);
        return switch (unit) {
            case "hour" -> local.plusHours(1).toOffsetDateTime();
            case "week" -> local.plusWeeks(1).toOffsetDateTime();
            case "month" -> local.plusMonths(1).toOffsetDateTime();
            default -> local.plusDays(1).toOffsetDateTime();
        };
    }

}
