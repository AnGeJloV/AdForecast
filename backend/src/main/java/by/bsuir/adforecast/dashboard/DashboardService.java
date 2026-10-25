package by.bsuir.adforecast.dashboard;

import by.bsuir.adforecast.campaign.CampaignSummaryProjection;
import by.bsuir.adforecast.repo.CampaignRepository;
import by.bsuir.adforecast.repo.ForecastRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@Transactional
public class DashboardService {

    private static final int COMPARABILITY_TOLERANCE_DAYS = 30;

    /** Гранулярность графика денежного потока: шаг корзины и её предел по умолчанию. */
    private static final String CASHFLOW_SQL = """
            select date_trunc(?, m.metric_date)::date as period_start,
                   sum(m.cost) as cost,
                   sum(m.revenue) as revenue
            from daily_metrics m
            join uploads u on u.upload_id = m.upload_id
            where u.campaign_id in (select campaign_id from campaign_access where user_id = ?)
            group by 1
            order by 1 desc
            limit ?
            """;

    /** «Без ограничения»: предел корзин, который заведомо больше любой истории. */
    private static final int ALL_BUCKETS = 100_000;

    /**
     * «План против факта»: факт завершённой кампании против последнего прогноза по ней.
     * Прогнозы-предупреждения (бюджет вне данных модели или ниже минимума площадки,
     * незнакомый канал) числовой оценки не содержат — сравнивать с фактом нечего,
     * поэтому кампания в отчёт не попадает.
     */
    private static final String PLAN_FACT_SQL = """
            with latest as (
                select distinct on (f.campaign_id) f.campaign_id, r.forecast_roi, r.verdict, p.duration_days
                from forecasts f
                join forecast_results r on r.forecast_id = f.forecast_id
                join forecast_params p on p.forecast_id = f.forecast_id
                where f.status = 'COMPLETED'
                order by f.campaign_id, f.started_at desc
            )
            select v.campaign_id as campaign_id,
                   v.campaign_name as campaign_name,
                   v.currency as currency,
                   v.platform as platform,
                   v.days_count as days_count,
                   v.actual_roi as actual_roi,
                   l.forecast_roi as forecast_roi,
                   l.duration_days as duration_days
            from v_campaign_summary v
            join latest l on l.campaign_id = v.campaign_id
            where v.status = 'FINISHED'
              and l.verdict in ('PROFITABLE', 'BORDERLINE', 'UNPROFITABLE')
              and v.campaign_id in (select campaign_id from campaign_access where user_id = ?)
            order by v.campaign_id
            """;

    private final CampaignRepository campaignRepository;
    private final ForecastRepository forecastRepository;
    private final JdbcTemplate jdbcTemplate;

    public DashboardService(CampaignRepository campaignRepository, ForecastRepository forecastRepository,
                            JdbcTemplate jdbcTemplate) {
        this.campaignRepository = campaignRepository;
        this.forecastRepository = forecastRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    public DashboardResponse build(long userId) {
        List<CampaignSummaryProjection> summaries = campaignRepository.findUserSummaries(userId);
        long total = summaries.size();
        long planned = summaries.stream().filter(s -> "PLANNED".equals(s.getStatus())).count();
        long active = summaries.stream().filter(s -> "ACTIVE".equals(s.getStatus())).count();
        long finished = total - planned - active;

        BigDecimal totalBudget = summaries.stream()
                .map(CampaignSummaryProjection::getBudget)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCost = summaries.stream()
                .map(CampaignSummaryProjection::getTotalCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalRevenue = summaries.stream()
                .map(CampaignSummaryProjection::getTotalRevenue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal actualRoi = totalCost.signum() == 0
                ? null
                : totalRevenue.subtract(totalCost)
                        .divide(totalCost, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(2, RoundingMode.HALF_UP);

        long profitable = summaries.stream()
                .filter(summary -> "FINISHED".equals(summary.getStatus()))
                .map(CampaignSummaryProjection::getActualRoi)
                .filter(roi -> roi != null && roi.signum() > 0)
                .count();
        long unprofitable = finished - profitable;

        long forecastsTotal = forecastRepository.countByUserId(userId);
        long forecastsCompleted = forecastRepository.countCompletedByUserId(userId);

        List<DashboardResponse.ChannelRow> channels = channels(summaries);

        List<DashboardResponse.PlanFactRow> planVsFact = planVsFact(userId);
        List<DashboardResponse.PlanFactRow> comparable = planVsFact.stream()
                .filter(DashboardResponse.PlanFactRow::comparable)
                .toList();
        // Знаковое отклонение и ошибка — разные вещи: первое показывает смещение модели,
        // второе — насколько она промахивается. Раньше наружу отдавалось только смещение,
        // но подписывалось как «средняя ошибка», и разброс прятался за взаимным гашением.
        BigDecimal planVsFactAvgError = null;
        BigDecimal planVsFactBias = null;
        BigDecimal planVsFactAvgErrorPercent = null;
        if (!comparable.isEmpty()) {
            BigDecimal count = BigDecimal.valueOf(comparable.size());
            BigDecimal absoluteSum = BigDecimal.ZERO;
            BigDecimal signedSum = BigDecimal.ZERO;
            BigDecimal relativeSum = BigDecimal.ZERO;
            for (DashboardResponse.PlanFactRow row : comparable) {
                absoluteSum = absoluteSum.add(row.deviation().abs());
                signedSum = signedSum.add(row.deviation());
                // коэффициент прогноза в процентных пунктах: 1,0 = 100
                BigDecimal plannedPoints = BigDecimal.valueOf(Math.max(100.0 + row.forecastRoi().doubleValue(), 1.0));
                relativeSum = relativeSum.add(row.deviation().abs()
                        .multiply(BigDecimal.valueOf(100))
                        .divide(plannedPoints, 4, RoundingMode.HALF_UP));
            }
            planVsFactAvgError = absoluteSum.divide(count, 2, RoundingMode.HALF_UP);
            planVsFactBias = signedSum.divide(count, 2, RoundingMode.HALF_UP);
            planVsFactAvgErrorPercent = relativeSum.divide(count, 2, RoundingMode.HALF_UP);
        }

        return new DashboardResponse(
                total, planned, active, finished,
                totalBudget, totalCost, totalRevenue, actualRoi,
                profitable, unprofitable,
                forecastsTotal, forecastsCompleted,
                channels, planVsFact, planVsFactAvgError, planVsFactBias, planVsFactAvgErrorPercent
        );
    }

    private List<DashboardResponse.ChannelRow> channels(List<CampaignSummaryProjection> summaries) {
        Map<String, List<CampaignSummaryProjection>> byPlatform = summaries.stream()
                .filter(summary -> "FINISHED".equals(summary.getStatus()))
                .filter(summary -> summary.getActualRoi() != null)
                .collect(java.util.stream.Collectors.groupingBy(CampaignSummaryProjection::getPlatform));
        return byPlatform.entrySet().stream()
                .map(entry -> {
                    List<CampaignSummaryProjection> rows = entry.getValue();
                    BigDecimal totalCost = rows.stream()
                            .map(CampaignSummaryProjection::getTotalCost)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal totalRevenue = rows.stream()
                            .map(CampaignSummaryProjection::getTotalRevenue)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal avgRoi = rows.stream()
                            .map(CampaignSummaryProjection::getActualRoi)
                            .reduce(BigDecimal.ZERO, BigDecimal::add)
                            .divide(BigDecimal.valueOf(rows.size()), 2, RoundingMode.HALF_UP);
                    long profitable = rows.stream()
                            .filter(summary -> summary.getActualRoi().signum() > 0)
                            .count();
                    return new DashboardResponse.ChannelRow(
                            entry.getKey(), rows.size(), totalCost, totalRevenue, avgRoi,
                            profitable, rows.size() - profitable);
                })
                .sorted(java.util.Comparator.comparing(DashboardResponse.ChannelRow::platform))
                .toList();
    }

    /**
     * Денежный поток корзинами выбранного шага. Без {@code limit} отдаётся вся история —
     * режим «за всё время». Шаг приходит от клиента, поэтому ограничен белым списком.
     */
    @Transactional(readOnly = true)
    public List<CashflowPoint> cashflow(long userId, String granularity, Integer limit) {
        String unit = switch (granularity == null ? "" : granularity.toUpperCase(Locale.ROOT)) {
            case "DAY" -> "day";
            case "MONTH" -> "month";
            case "QUARTER" -> "quarter";
            default -> "week";
        };
        int buckets = limit == null ? ALL_BUCKETS : Math.min(Math.max(limit, 1), 120);
        List<CashflowPoint> points = jdbcTemplate.query(CASHFLOW_SQL, (rs, rowNum) -> new CashflowPoint(
                rs.getDate("period_start").toLocalDate(),
                rs.getBigDecimal("cost"),
                rs.getBigDecimal("revenue")
        ), unit, userId, buckets);
        return points.reversed();
    }

    private List<DashboardResponse.PlanFactRow> planVsFact(long userId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(PLAN_FACT_SQL, userId);
        return rows.stream()
                .map(row -> {
                    BigDecimal actualRoi = row.get("actual_roi") == null ? null : (BigDecimal) row.get("actual_roi");
                    BigDecimal forecastRoi = (BigDecimal) row.get("forecast_roi");
                    long days = ((Number) row.get("days_count")).longValue();
                    long durationDays = ((Number) row.get("duration_days")).longValue();
                    boolean comparable = Math.abs(days - durationDays) <= COMPARABILITY_TOLERANCE_DAYS;
                    BigDecimal deviation = actualRoi == null || !comparable
                            ? null
                            : actualRoi.subtract(forecastRoi).setScale(2, RoundingMode.HALF_UP);
                    return new DashboardResponse.PlanFactRow(
                            ((Number) row.get("campaign_id")).longValue(),
                            String.valueOf(row.get("campaign_name")),
                            String.valueOf(row.get("currency")),
                            String.valueOf(row.get("platform")),
                            actualRoi,
                            forecastRoi,
                            deviation,
                            days,
                            durationDays,
                            comparable
                    );
                })
                .toList();
    }
}
