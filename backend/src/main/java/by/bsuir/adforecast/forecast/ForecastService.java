package by.bsuir.adforecast.forecast;

import by.bsuir.adforecast.audit.AuditService;
import by.bsuir.adforecast.campaign.CampaignService;
import by.bsuir.adforecast.domain.Campaign;
import by.bsuir.adforecast.domain.CampaignStatus;
import by.bsuir.adforecast.domain.Forecast;
import by.bsuir.adforecast.domain.ForecastParam;
import by.bsuir.adforecast.domain.ForecastResult;
import by.bsuir.adforecast.domain.ForecastStatus;
import by.bsuir.adforecast.domain.MlModel;
import by.bsuir.adforecast.mlclient.MlModelInfo;
import by.bsuir.adforecast.mlclient.MlObservedState;
import by.bsuir.adforecast.mlclient.MlPredictCommand;
import by.bsuir.adforecast.mlclient.MlPredictResponse;
import by.bsuir.adforecast.mlclient.MlServiceClient;
import by.bsuir.adforecast.mlclient.MlServiceException;
import by.bsuir.adforecast.organization.PlatformCatalog;
import by.bsuir.adforecast.repo.AdAccountRepository;
import by.bsuir.adforecast.repo.CampaignRepository;
import by.bsuir.adforecast.repo.ForecastParamRepository;
import by.bsuir.adforecast.repo.ForecastRepository;
import by.bsuir.adforecast.repo.ForecastResultRepository;
import by.bsuir.adforecast.repo.MlModelRepository;
import by.bsuir.adforecast.repo.UserRepository;
import by.bsuir.adforecast.training.OrgModelService;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ForecastService {

    private static final Logger log = LoggerFactory.getLogger(ForecastService.class);

    static final String GENERAL_MODEL_NAME = "adforecast_roi";

    /** Константа плавного смешивания: вес своей модели = n / (n + K). */
    private static final double OWN_HISTORY_CONSTANT = 25.0;

    /** Порог обучения модели организации — совпадает с TrainingService.MIN_CAMPAIGNS. */
    private static final int MIN_CAMPAIGNS_FOR_OWN_MODEL = 5;

    /** Дневной бюджет, ниже которого рекламные площадки не показывают объявления. */
    private static final double MIN_MEANINGFUL_DAY_BUDGET = 20.0;
    private static final double DAYS_PER_MONTH = 30.44;
    private static final double LAG_FRACTION = 0.5;

    private static final String LIST_ALL_SQL = """
            select f.forecast_id, f.campaign_id, c.campaign_name, f.started_at, f.status,
                   r.verdict, r.payback_ratio, r.forecast_roi, r.interval_lower, r.interval_upper,
                   p.duration_days, r.payback_months, r.mape
            from forecasts f
            join campaigns c on c.campaign_id = f.campaign_id
            left join forecast_params p on p.forecast_id = f.forecast_id
            left join forecast_results r on r.forecast_id = f.forecast_id
            where f.campaign_id in (select campaign_id from campaign_access where user_id = ?)
            order by f.started_at desc
            limit 200
            """;

    private static final String OBSERVED_SQL = """
            select count(*) as days,
                   sum(m.cost) as cost,
                   sum(m.revenue) as revenue,
                   sum(m.cost) filter (where m.metric_date >= (select max(m2.metric_date)
                       from daily_metrics m2
                       join uploads u2 on u2.upload_id = m2.upload_id
                       where u2.campaign_id = ?) - 6) as cost_last7,
                   sum(m.revenue) filter (where m.metric_date >= (select max(m2.metric_date)
                       from daily_metrics m2
                       join uploads u2 on u2.upload_id = m2.upload_id
                       where u2.campaign_id = ?) - 6) as revenue_last7,
                   min(m.metric_date) as first_day
            from daily_metrics m
            join uploads u on u.upload_id = m.upload_id
            where u.campaign_id = ?
            """;

    private static final String OWN_FACTS_SQL = """
            select sum(m.cost) as cost,
                   sum(m.revenue) as revenue,
                   count(m.metric_id) as days
            from campaigns c
            join uploads u on u.campaign_id = c.campaign_id
            join daily_metrics m on m.upload_id = u.upload_id
            where c.organization_id = ? and c.status = 'FINISHED' and c.in_training and not c.is_test
            group by c.campaign_id
            having sum(m.cost) > 0
            """;

    private static final String OWN_CAMPAIGNS_SQL = """
            select count(distinct c.campaign_id)
            from campaigns c
            where c.organization_id = ? and c.status = 'FINISHED' and c.in_training and not c.is_test
              and exists (select 1 from uploads u
                          join daily_metrics m on m.upload_id = u.upload_id
                          where u.campaign_id = c.campaign_id)
            """;

    private final CampaignRepository campaignRepository;
    private final CampaignService campaignService;
    private final AdAccountRepository adAccountRepository;
    private final ForecastRepository forecastRepository;
    private final ForecastParamRepository forecastParamRepository;
    private final ForecastResultRepository forecastResultRepository;
    private final MlModelRepository mlModelRepository;
    private final UserRepository userRepository;
    private final MlServiceClient mlServiceClient;
    private final OrgModelService orgModelService;
    private final AuditService auditService;
    private final JdbcTemplate jdbcTemplate;

    public ForecastService(CampaignRepository campaignRepository,
                           CampaignService campaignService,
                           AdAccountRepository adAccountRepository,
                           ForecastRepository forecastRepository,
                           ForecastParamRepository forecastParamRepository,
                           ForecastResultRepository forecastResultRepository,
                           MlModelRepository mlModelRepository,
                           UserRepository userRepository,
                           MlServiceClient mlServiceClient,
                           OrgModelService orgModelService,
                           AuditService auditService,
                           JdbcTemplate jdbcTemplate) {
        this.campaignRepository = campaignRepository;
        this.campaignService = campaignService;
        this.adAccountRepository = adAccountRepository;
        this.forecastRepository = forecastRepository;
        this.forecastParamRepository = forecastParamRepository;
        this.forecastResultRepository = forecastResultRepository;
        this.mlModelRepository = mlModelRepository;
        this.userRepository = userRepository;
        this.mlServiceClient = mlServiceClient;
        this.orgModelService = orgModelService;
        this.auditService = auditService;
        this.jdbcTemplate = jdbcTemplate;
    }

    public ForecastResponse run(long campaignId, long userId, ForecastRequest request) {
        Campaign campaign = campaignRepository.findWithAccount(campaignId)
                .orElseThrow(() -> new EntityNotFoundException("кампания не найдена"));
        campaignService.requireAccess(userId, campaignId);
        if (campaign.getStatus() == CampaignStatus.FINISHED) {
            throw new IllegalArgumentException("кампания завершена, фактическая окупаемость уже известна");
        }

        // Запись создается до обращения к ML: неудачная попытка тоже должна остаться
        // в истории и журнале. Модель назначается после успешного расчета.
        Forecast forecast = new Forecast();
        forecast.setCampaign(campaign);
        forecast.setStartedAt(LocalDateTime.now());
        forecast.setStatus(ForecastStatus.PENDING);
        forecast = forecastRepository.save(forecast);

        ForecastParam param = null;
        ForecastResult result = null;
        Prediction prediction = null;
        try {
            forecast.setStatus(ForecastStatus.RUNNING);
            forecastRepository.save(forecast);

            ObservedSnapshot snapshot = observedState(campaignId);
            int duration = resolveDuration(request, snapshot);
            int startMonth = resolveStartMonth(request, snapshot);
            String scope = request.scopeOrDefault();
            // модель считает в BYN: бюджет кампании приводится к базовой валюте по курсу НБРБ
            BigDecimal budgetBase = CurrencyRates.toBase(campaign.getCurrency(), campaign.getBudget());

            prediction = blendedPrediction(campaign, budgetBase, duration, startMonth, snapshot,
                    campaign.getOrganization().getId(), scope);
            forecast.setModel(prediction.dominantModel());
            forecastRepository.save(forecast);

            param = new ForecastParam();
            param.setForecast(forecast);
            param.setDurationDays(duration);
            param.setStartMonth(startMonth);
            param.setHistDays(snapshot == null ? 0 : snapshot.days());
            param.setHistCost(snapshot == null ? null : BigDecimal.valueOf(snapshot.cost()).setScale(2, RoundingMode.HALF_UP));
            param.setHistRevenue(snapshot == null ? null : BigDecimal.valueOf(snapshot.revenue()).setScale(2, RoundingMode.HALF_UP));
            param.setBudget(campaign.getBudget());
            param.setCurrency(campaign.getCurrency());
            param.setConfidenceLevel(new BigDecimal("0.8"));
            param.setUseSeasonality(true);
            forecastParamRepository.save(param);

            result = new ForecastResult();
            result.setForecast(forecast);
            result.setVerdict(prediction.verdict());
            // Вердикт-предупреждение числовой оценки не содержит: пустые поля честнее фиктивных.
            // Иначе коэффициент 0 превращался в ROI −100 %, и это уходило в «план против факта»,
            // отчёты и счётчик успешных прогнозов.
            if (numericVerdict(prediction.verdict())) {
                fillNumbers(result, campaign, prediction);
            }
            result.setBudgetAny(prediction.budgetAny());
            result.setModelScope(scope);
            // границы дневного бюджета обучающих данных значимы и в предупреждении:
            // по ним видно, какой бюджет модель вообще способна оценить
            result.setDayBudgetMin(prediction.dayBudgetMin() == null
                    ? null : CurrencyRates.fromBase(campaign.getCurrency(), prediction.dayBudgetMin()));
            result.setDayBudgetMax(prediction.dayBudgetMax() == null
                    ? null : CurrencyRates.fromBase(campaign.getCurrency(), prediction.dayBudgetMax()));
            result.setDataNote(truncate(prediction.dataNote()));
            result.setOwnWeight(BigDecimal.valueOf(prediction.ownWeight()).setScale(3, RoundingMode.HALF_UP));
            result.setOwnModel(prediction.ownModel());
            forecastResultRepository.save(result);

            forecast.setStatus(ForecastStatus.COMPLETED);
            forecastRepository.save(forecast);
        } catch (RuntimeException exception) {
            markFailed(userId, forecast, campaign, exception);
            throw exception;
        }

        auditService.record(userId, "запуск прогноза", "кампания", campaignId,
                "«" + campaign.getCampaignName() + "»: вердикт «" + verdictLabel(prediction.verdict())
                        + "», коэффициент " + toRatio(prediction.ratio()).toPlainString()
                        + " (интервал " + String.format("%.2f", prediction.lower()) + "–"
                        + String.format("%.2f", prediction.upper()) + ")"
                        + ", длительность " + param.getDurationDays() + " дн., область: "
                        + scopeLabel(result.getModelScope())
                        + ", модели: своя " + (prediction.ownModel() == null
                                ? "не использовалась"
                                : prediction.ownModel().getModelVersion() + " (вес "
                                        + String.format("%.2f", prediction.ownWeight()) + ")")
                        + " + общая " + prediction.dominantModel().getModelVersion());
        return toResponse(forecast, param, result, prediction);
    }

    /** Фиксирует неудачную попытку, чтобы она была видна в истории и журнале действий. */
    private void markFailed(long userId, Forecast forecast, Campaign campaign, RuntimeException exception) {
        forecast.setStatus(ForecastStatus.FAILED);
        forecastRepository.save(forecast);
        auditService.record(userId, "прогноз не рассчитан", "кампания", campaign.getId(),
                truncate("«" + campaign.getCampaignName() + "»: " + exception.getMessage()));
    }

    @Transactional(readOnly = true)
    public List<ForecastResponse> history(long campaignId, long userId) {
        campaignRepository.findById(campaignId)
                .orElseThrow(() -> new EntityNotFoundException("кампания не найдена"));
        campaignService.requireAccess(userId, campaignId);
        return historyOf(campaignId);
    }

    /** Без проверки владения: доступ ограничивает админский контроллер. */
    @Transactional(readOnly = true)
    public List<ForecastResponse> historyForInspection(long campaignId) {
        campaignRepository.findById(campaignId)
                .orElseThrow(() -> new EntityNotFoundException("кампания не найдена"));
        return historyOf(campaignId);
    }

    private List<ForecastResponse> historyOf(long campaignId) {
        return forecastRepository.findByCampaignIdOrderByStartedAtDesc(campaignId).stream()
                .map(forecast -> {
                    ForecastParam param = forecastParamRepository.findByForecastId(forecast.getId()).orElse(null);
                    ForecastResult result = forecastResultRepository.findByForecastId(forecast.getId()).orElse(null);
                    return toHistoryItem(forecast, param, result);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ForecastListItem> listAll(long userId) {
        return jdbcTemplate.query(LIST_ALL_SQL, (rs, rowNum) -> new ForecastListItem(
                rs.getLong("forecast_id"),
                rs.getLong("campaign_id"),
                rs.getString("campaign_name"),
                rs.getTimestamp("started_at").toLocalDateTime(),
                rs.getString("status"),
                rs.getString("verdict"),
                rs.getBigDecimal("payback_ratio"),
                rs.getBigDecimal("forecast_roi"),
                rs.getBigDecimal("interval_lower"),
                rs.getBigDecimal("interval_upper"),
                rs.getObject("duration_days", Integer.class),
                rs.getBigDecimal("payback_months"),
                rs.getBigDecimal("mape")
        ), userId);
    }

    @Transactional(readOnly = true)
    public List<CompareResponse> compare(long userId, CompareRequest request) {
        Set<String> platforms = adAccountRepository.findAll().stream()
                .map(account -> account.getPlatform())
                .collect(Collectors.toSet());
        for (CompareRequest.CompareVariant variant : request.variants()) {
            if (!platforms.contains(variant.platform())) {
                throw new IllegalArgumentException("неизвестная платформа: " + variant.platform());
            }
        }
        Long organizationId = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("пользователь не найден"))
                .getOrganization().getId();
        orgModelService.triggerIfStale(organizationId);
        String scope = normalizeScope(request.scope());
        List<CompareResponse> responses = new ArrayList<>();
        for (CompareRequest.CompareVariant variant : request.variants()) {
            String currency = variant.currency() == null || variant.currency().isBlank()
                    ? CurrencyRates.BASE : variant.currency();
            if (!CurrencyRates.isSupported(currency)) {
                throw new IllegalArgumentException("валюта «" + currency + "» не поддерживается — доступны "
                        + String.join(", ", CurrencyRates.supported()));
            }
            BigDecimal budgetBase = CurrencyRates.toBase(currency, variant.budget());
            MlPredictCommand command = new MlPredictCommand(
                    variant.platform(),
                    budgetBase.doubleValue(),
                    variant.durationDays(),
                    variant.startMonth(),
                    null,
                    GENERAL_MODEL_NAME,
                    activeGeneral().map(MlModel::getModelVersion).orElse(null));
            Prediction prediction = blend(command, organizationId, budgetBase.doubleValue(),
                    variant.durationDays(), null, scope);
            responses.add(new CompareResponse(
                    variant.platform(),
                    variant.budget(),
                    currency,
                    variant.durationDays(),
                    variant.startMonth(),
                    prediction.verdict(),
                    toRatio(prediction.ratio()),
                    BigDecimal.valueOf((prediction.ratio() - 1.0) * 100).setScale(2, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(prediction.lower()).setScale(4, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(prediction.upper()).setScale(4, RoundingMode.HALF_UP),
                    prediction.paybackDays() == null
                            ? null
                            : BigDecimal.valueOf(prediction.paybackDays() / DAYS_PER_MONTH).setScale(2, RoundingMode.HALF_UP),
                    prediction.paybackDays() != null && prediction.paybackDays() <= variant.durationDays(),
                    prediction.budgetMin() == null
                            ? null : CurrencyRates.fromBase(currency, prediction.budgetMin()),
                    prediction.budgetMax() == null
                            ? null : CurrencyRates.fromBase(currency, prediction.budgetMax()),
                    prediction.budgetAny(),
                    prediction.basis()
            ));
        }
        return responses;
    }

    /** Область расчёта из запроса: пустое/незнакомое значение → BLEND. */
    private static String normalizeScope(String scope) {
        if (scope == null) {
            return "BLEND";
        }
        String trimmed = scope.trim().toUpperCase();
        return "OWN".equals(trimmed) || "GENERAL".equals(trimmed) ? trimmed : "BLEND";
    }

    // ----- смешивание моделей и расчёт -----

    private record Prediction(double ratio, double lower, double upper, double agreement, Double paybackDays,
                              Double budgetMin, Double budgetMax, boolean budgetAny, boolean budgetOutOfRange,
                              Double dayBudgetMin, Double dayBudgetMax, String verdict, boolean baseline,
                              MlModel dominantModel, MlModel ownModel, double ownWeight,
                              int ownCampaigns, String basis, String dataNote) {
    }

    private Prediction blendedPrediction(Campaign campaign, BigDecimal budgetBase, int duration, int startMonth,
                                         ObservedSnapshot snapshot, long organizationId, String scope) {
        orgModelService.triggerIfStale(organizationId);
        return blend(new MlPredictCommand(
                campaign.getAccount().getPlatform(),
                budgetBase.doubleValue(),
                duration,
                startMonth,
                snapshot == null ? null : new MlObservedState(snapshot.days(), snapshot.cost(), snapshot.revenue(),
                        snapshot.costLast7(), snapshot.revenueLast7()),
                GENERAL_MODEL_NAME,
                activeGeneral().map(MlModel::getModelVersion).orElse(null)
        ), organizationId, budgetBase.doubleValue(), duration, snapshot, scope);
    }

    /**
     * Прогноз с выбранной областью: BLEND — своя модель вместе с общей, OWN — только
     * данные организации (при отсутствии модели — простая оценка по её фактам), GENERAL —
     * только модель системы.
     */
    private Prediction blend(MlPredictCommand command, long organizationId, double budget, int duration,
                             ObservedSnapshot snapshot, String scope) {
        MlModel general = activeGeneral()
                .orElseThrow(() -> new MlServiceException("нет обученных моделей прогнозирования"));
        int ownCampaigns = countOwnCampaigns(organizationId);
        MlModel own = activeOwn(organizationId).orElse(null);

        // минимальный бюджет площадки — реальное ограничение, из каталога с источником
        BigDecimal dayBudget = BigDecimal.valueOf(budget)
                .divide(BigDecimal.valueOf(duration), 4, RoundingMode.HALF_UP);
        PlatformCatalog.PlatformInfo platform = PlatformCatalog.find(command.platform()).orElse(null);
        if (platform != null && platform.minDayBudget().signum() > 0
                && dayBudget.compareTo(platform.minDayBudget()) < 0) {
            return warning("BELOW_MINIMUM", general, own, ownCampaigns,
                    "бюджет ниже минимума площадки «" + platform.name() + "»: нужно не меньше "
                            + platform.minDayBudget().stripTrailingZeros().toPlainString()
                            + " BYN в день, у вас " + dayBudget.stripTrailingZeros().toPlainString()
                            + " BYN в день",
                    platform.note());
        }

        if ("OWN".equals(scope) && own == null) {
            return baselinePrediction(organizationId, budget, duration, snapshot, ownCampaigns, general);
        }

        double weight = "GENERAL".equals(scope) || own == null
                ? 0.0
                : "OWN".equals(scope) ? 1.0 : ownCampaigns / (ownCampaigns + OWN_HISTORY_CONSTANT);
        MlPredictResponse generalPrediction = weight >= 1.0 ? null : mlServiceClient.predict(command);

        MlPredictResponse ownPrediction = null;
        if (weight > 0.0) {
            try {
                ownPrediction = mlServiceClient.predict(new MlPredictCommand(
                        command.platform(), command.budget(), command.durationDays(), command.startMonth(),
                        command.observed(),
                        ownModelName(organizationId), own.getModelVersion()));
            } catch (MlServiceException exception) {
                if ("OWN".equals(scope)) {
                    throw exception;
                }
                log.warn("прогноз своей моделью недоступен, используется общая: {}", exception.getMessage());
                weight = 0.0;
                own = null;
            }
        }

        // канал, которого нет в данных моделей: обе ответили platform_unknown. Сохраняем «сырые»
        // ответы, чтобы вместо отказа дать грубую прикидку по опыту модели — с явным предупреждением
        // о низкой уверенности. Точность вырастет, когда по каналу появятся завершённые кампании.
        boolean ownUnknown = ownPrediction != null && ownPrediction.platformUnknown();
        boolean generalUnknown = generalPrediction != null && generalPrediction.platformUnknown();
        MlPredictResponse generalRaw = generalPrediction;
        MlPredictResponse ownRaw = ownPrediction;
        if (ownUnknown) {
            ownPrediction = null;
        }
        if (generalUnknown) {
            generalPrediction = null;
        }
        boolean channelUnknown = ownPrediction == null && generalPrediction == null;
        if (channelUnknown) {
            MlPredictResponse fallback = generalRaw != null ? generalRaw : ownRaw;
            if (fallback == null) {
                return warning("NO_CHANNEL_DATA", general, own, ownCampaigns,
                        "канал «" + command.platform() + "» пока не встречался в данных модели — прогноз по нему невозможен",
                        "Общая модель знает только каналы из своих обучающих данных. Своя модель обучится"
                                + " на этом канале, когда появятся ваши завершённые кампании — до тех пор прогноз"
                                + " по нему не выдается.");
            }
            generalPrediction = fallback;
            weight = 0.0;
        }
        if (generalPrediction == null) {
            weight = 1.0;
        } else if (ownPrediction == null) {
            weight = 0.0;
        }

        double ownRatio = ownPrediction == null ? 0.0 : ownPrediction.paybackRatio();
        double generalRatio = generalPrediction == null ? 0.0 : generalPrediction.paybackRatio();
        double ratio = weight * ownRatio + (1.0 - weight) * generalRatio;
        double ownHalf = ownPrediction == null
                ? 0.0
                : (ownPrediction.intervalUpper() - ownPrediction.intervalLower()) / 2.0;
        double generalHalf = generalPrediction == null
                ? 0.0
                : (generalPrediction.intervalUpper() - generalPrediction.intervalLower()) / 2.0;
        double meanGap = ownPrediction == null || generalPrediction == null ? 0.0 : ownRatio - generalRatio;
        double half = Math.sqrt(Math.pow(weight * ownHalf, 2)
                + Math.pow((1.0 - weight) * generalHalf, 2)
                + weight * (1.0 - weight) * Math.pow(meanGap, 2));
        double agreement = weight * (ownPrediction == null ? 0.0 : ownPrediction.agreement())
                + (1.0 - weight) * (generalPrediction == null ? 0.0 : generalPrediction.agreement());
        MlPredictResponse dominantPrediction = ownPrediction != null && weight >= 0.5
                ? ownPrediction : generalPrediction == null ? ownPrediction : generalPrediction;
        Double budgetMin = dominantPrediction.profitableBudgetMin();
        Double budgetMax = dominantPrediction.profitableBudgetMax();
        boolean budgetAny = dominantPrediction.profitableAtAnyBudget();
        boolean outOfRange = dominantPrediction.budgetOutOfRange();
        Double dayBudgetMin = dominantPrediction.dayBudgetMin() > 0 ? dominantPrediction.dayBudgetMin() : null;
        Double dayBudgetMax = dominantPrediction.dayBudgetMax() > 0 ? dominantPrediction.dayBudgetMax() : null;
        Double paybackDays = outOfRange ? null : paybackDays(ratio, duration, budget, snapshot);

        double lower = Math.max(0.0, ratio - half);
        double upper = ratio + half;
        String verdict = outOfRange ? "OUT_OF_RANGE"
                : lower >= 1.0 ? "PROFITABLE" : upper < 1.0 ? "UNPROFITABLE" : "BORDERLINE";

        MlModel dominant = weight >= 0.5 && own != null ? own : general;
        String dataNote = channelUnknown
                ? "канал «" + command.platform() + "» пока не встречался в обучающих данных — это грубая прикидка"
                        + " по опыту похожих кампаний, точность низкая. Она уточнится, когда по этому каналу"
                        + " появится достаточно завершённых кампаний (модели дообучатся автоматически)."
                : "OWN".equals(scope)
                        ? "прогноз только по данным вашей организации: модель обучена на " + ownCampaigns
                                + " кампаниях, общая модель не используется"
                        : null;
        return new Prediction(ratio, lower, upper, agreement, paybackDays, budgetMin, budgetMax, budgetAny,
                outOfRange, dayBudgetMin, dayBudgetMax, verdict, false,
                dominant, own, weight, ownCampaigns,
                basis(scope, weight, ownCampaigns, snapshot), dataNote);
    }

    /** Ответ без числового прогноза: бюджет или канал вне возможностей модели. */
    private Prediction warning(String verdict, MlModel general, MlModel own, int ownCampaigns,
                               String basis, String dataNote) {
        return new Prediction(0.0, 0.0, 0.0, 0.0, null, null, null, false, false, null, null,
                verdict, false, general, own, 0.0, ownCampaigns, basis, dataNote);
    }

    /**
     * Оценка по своим данным без модели: используется, когда маркетолог просит опираться
     * только на свою организацию, а своей модели ещё нет (мало завершённых кампаний).
     * Считается средняя фактическая окупаемость его кампаний, интервал — по разбросу.
     */
    private Prediction baselinePrediction(long organizationId, double budget, int duration,
                                          ObservedSnapshot snapshot, int ownCampaigns, MlModel general) {
        List<double[]> points = new ArrayList<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(OWN_FACTS_SQL, organizationId);
        for (Map<String, Object> row : rows) {
            double cost = ((Number) row.get("cost")).doubleValue();
            double revenue = ((Number) row.get("revenue")).doubleValue();
            if (cost > 0) {
                points.add(new double[]{cost, revenue / cost, cost / ((Number) row.get("days")).doubleValue()});
            }
        }
        if (snapshot != null && snapshot.cost() > 0) {
            points.add(new double[]{snapshot.cost(), snapshot.revenue() / snapshot.cost(),
                    snapshot.cost() / snapshot.days()});
        }
        if (points.isEmpty()) {
            throw new IllegalArgumentException(
                    "оценка только по своим данным невозможна: ни у одной вашей кампании нет загруженных выгрузок"
                            + " — выберите «своя + общая» или «только общая модель»");
        }

        double costSum = 0.0;
        for (double[] point : points) {
            costSum += point[0];
        }
        double estimate = 0.0;
        for (double[] point : points) {
            estimate += point[0] * point[1] / costSum;
        }
        double variance = 0.0;
        for (double[] point : points) {
            variance += point[0] * Math.pow(point[1] - estimate, 2) / costSum;
        }
        double deviation = Math.sqrt(variance);
        double half = points.size() == 1
                ? Math.max(0.45 * estimate, 0.3)
                : Math.max(1.5 * deviation, 0.2 * estimate);
        double lower = Math.max(0.0, estimate - half);
        double upper = estimate + half;

        double dayBudget = budget / duration;
        double minDay = points.stream().mapToDouble(point -> point[2]).min().orElse(0.0);
        double maxDay = points.stream().mapToDouble(point -> point[2]).max().orElse(0.0);
        // нижняя граница — физическая (при таких ставках реклама не показывается),
        // верхняя — с большим допуском: у организации может быть всего одна кампания-эталон
        boolean outOfRange = dayBudget < MIN_MEANINGFUL_DAY_BUDGET
                || (maxDay > 0 && dayBudget > maxDay * 10);
        String verdict = outOfRange ? "OUT_OF_RANGE"
                : lower >= 1.0 ? "PROFITABLE" : upper < 1.0 ? "UNPROFITABLE" : "BORDERLINE";
        Double paybackDays = outOfRange ? null : paybackDays(estimate, duration, budget, snapshot);

        String campaigns = points.size() == 1
                ? "одной вашей кампании"
                : points.size() + " вашим кампаниям";
        String basis = "оценка по " + campaigns + (ownCampaigns >= MIN_CAMPAIGNS_FOR_OWN_MODEL
                ? " (своя модель ещё не готова — обучение не завершилось)"
                : " (своя модель ещё не обучена — нужно " + MIN_CAMPAIGNS_FOR_OWN_MODEL
                        + " завершённых кампаний, сейчас " + ownCampaigns + ")");
        String dataNote = points.size() < 3
                ? "данных очень мало: оценка — это средняя окупаемость по вашим выгрузкам, а не прогноз по параметрам"
                        + " кампании; ориентируйтесь на интервал"
                : "оценка по вашим данным: своя модель пока не обучена, общая не используется";

        return new Prediction(estimate, lower, upper, 0.0, paybackDays, null, null, false, outOfRange,
                MIN_MEANINGFUL_DAY_BUDGET, maxDay > 0 ? maxDay * 10 : null, verdict, true,
                general, null, 0.0, ownCampaigns, basis, dataNote);
    }

    private String basis(String scope, double weight, int ownCampaigns, ObservedSnapshot snapshot) {
        StringBuilder text = new StringBuilder();
        if ("GENERAL".equals(scope)) {
            text.append("только по общим данным рынка (модель системы), ваши кампании не учитывались");
        } else if ("OWN".equals(scope)) {
            text.append("по ").append(ownCampaigns).append(" завершённым кампаниям вашей организации,");
            text.append(" общая модель не использовалась");
        } else if (weight >= 0.995) {
            text.append("по ").append(ownCampaigns).append(" завершённым кампаниям вашей организации");
        } else if (weight > 0.005) {
            int ownShare = (int) Math.round(weight * 100);
            text.append("на ").append(ownShare).append(" % по кампаниям вашей организации (").append(ownCampaigns)
                    .append(" завершено), остальное — по общим данным");
        } else if (ownCampaigns > 0) {
            text.append("по общим данным рынка, своя модель ещё не обучена (завершённых кампаний: ")
                    .append(ownCampaigns).append(", модель обновляется автоматически)");
        } else {
            text.append("по общим данным рынка, у вашей организации пока нет завершённых кампаний");
        }
        if (snapshot != null && snapshot.days() > 0) {
            text.append("; учитывает данные этой кампании за ").append(snapshot.days()).append(" дней");
        }
        return text.toString();
    }

    /** Примечания хранятся в строке ограниченной длины. */
    private static String truncate(String text) {
        if (text == null || text.length() <= 250) {
            return text;
        }
        return text.substring(0, 247) + "...";
    }

    private static String scopeLabel(String scope) {
        return switch (scope) {
            case "OWN" -> "только свои данные";
            case "GENERAL" -> "только общая модель";
            default -> "своя и общая модели";
        };
    }

    private Double paybackDays(double ratio, int duration, double budget, ObservedSnapshot snapshot) {
        if (snapshot == null) {
            if (ratio <= 1.0) {
                return null;
            }
            double lag = LAG_FRACTION * duration;
            double inside = ratio * lag / (ratio - 1.0);
            return inside <= duration ? inside : duration / ratio + lag;
        }
        if (snapshot.revenue() >= snapshot.cost() && snapshot.cost() > 0) {
            return (double) snapshot.days();
        }
        int remaining = duration - snapshot.days();
        if (remaining <= 0) {
            return null;
        }
        double dailyCost = budget / duration;
        double totalCost = snapshot.cost() + dailyCost * remaining;
        double totalRevenue = ratio * totalCost;
        double remainingRevenue = Math.max(0.0, totalRevenue - snapshot.revenue());
        double dailyRevenue = remainingRevenue / remaining;
        if (dailyRevenue <= dailyCost) {
            return null;
        }
        double days = snapshot.days() + (snapshot.cost() - snapshot.revenue()) / (dailyRevenue - dailyCost);
        return days <= duration ? days : null;
    }

    // ----- вспомогательные -----

    private record ObservedSnapshot(int days, double cost, double revenue, double costLast7, double revenueLast7,
                                     LocalDate firstDay) {
    }

    private ObservedSnapshot observedState(long campaignId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(OBSERVED_SQL, campaignId, campaignId, campaignId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        Number days = (Number) row.get("days");
        if (days == null || days.intValue() == 0) {
            return null;
        }
        return new ObservedSnapshot(
                days.intValue(),
                ((Number) row.get("cost")).doubleValue(),
                ((Number) row.get("revenue")).doubleValue(),
                ((Number) row.get("cost_last7")).doubleValue(),
                ((Number) row.get("revenue_last7")).doubleValue(),
                row.get("first_day") instanceof java.sql.Date date ? date.toLocalDate() : null);
    }

    private int resolveDuration(ForecastRequest request, ObservedSnapshot snapshot) {
        Integer requested = request.durationDays();
        if (snapshot == null) {
            if (requested == null) {
                throw new IllegalArgumentException("укажите длительность кампании в днях");
            }
            return requested;
        }
        if (requested == null) {
            return snapshot.days() + 30;
        }
        // Длительность, равная числу дней данных, допустима: кампания заканчивается сегодня.
        // Без этого план всегда покрывал бы больший срок, чем факт, и сравнение было бы смещено.
        if (requested < snapshot.days()) {
            throw new IllegalArgumentException(
                    "кампания уже длится " + snapshot.days() + " дней — полная длительность не может быть меньше");
        }
        return requested;
    }

    private int resolveStartMonth(ForecastRequest request, ObservedSnapshot snapshot) {
        if (request.startMonth() != null) {
            return request.startMonth();
        }
        if (snapshot != null && snapshot.firstDay() != null) {
            return snapshot.firstDay().getMonthValue();
        }
        return LocalDate.now().getMonthValue();
    }

    private int countOwnCampaigns(long organizationId) {
        Integer count = jdbcTemplate.queryForObject(OWN_CAMPAIGNS_SQL, Integer.class, organizationId);
        return count == null ? 0 : count;
    }

    private Optional<MlModel> activeGeneral() {
        Optional<MlModel> active = mlModelRepository.findByOrganizationIdIsNullAndIsActiveTrue();
        if (active.isPresent()) {
            return active;
        }
        List<MlModelInfo> models = mlServiceClient.models().stream()
                .filter(info -> GENERAL_MODEL_NAME.equals(info.modelName()))
                .toList();
        if (models.isEmpty()) {
            return Optional.empty();
        }
        MlModelInfo latest = models.get(models.size() - 1);
        Optional<MlModel> registered = mlModelRepository
                .findByModelNameAndModelVersion(GENERAL_MODEL_NAME, latest.version());
        if (registered.isPresent()) {
            MlModel model = registered.get();
            model.setIsActive(true);
            return Optional.of(mlModelRepository.save(model));
        }
        MlModel model = new MlModel();
        model.setModelName(GENERAL_MODEL_NAME);
        model.setModelVersion(latest.version());
        model.setTrainedAt(OffsetDateTime.parse(latest.trainedAt()).toLocalDateTime());
        model.setQualityMetric(BigDecimal.valueOf(latest.mape()));
        model.setIsActive(true);
        return Optional.of(mlModelRepository.save(model));
    }

    private Optional<MlModel> activeOwn(long organizationId) {
        return mlModelRepository.findByOrganizationIdAndIsActiveTrue(organizationId);
    }

    static String ownModelName(long organizationId) {
        return "adforecast_org_" + organizationId;
    }

    /** Числовые поля результата — только у прогнозов, содержащих оценку. */
    private void fillNumbers(ForecastResult result, Campaign campaign, Prediction prediction) {
        result.setPaybackRatio(toRatio(prediction.ratio()));
        result.setForecastRoi(BigDecimal.valueOf((prediction.ratio() - 1.0) * 100).setScale(2, RoundingMode.HALF_UP));
        result.setIntervalLower(BigDecimal.valueOf(prediction.lower()).setScale(4, RoundingMode.HALF_UP));
        result.setIntervalUpper(BigDecimal.valueOf(prediction.upper()).setScale(4, RoundingMode.HALF_UP));
        result.setAgreement(prediction.baseline()
                ? null
                : BigDecimal.valueOf(prediction.agreement()).setScale(2, RoundingMode.HALF_UP));
        result.setPaybackDays(prediction.paybackDays() == null
                ? null : BigDecimal.valueOf(prediction.paybackDays()).setScale(1, RoundingMode.HALF_UP));
        result.setPaybackMonths(prediction.paybackDays() == null
                ? null : BigDecimal.valueOf(prediction.paybackDays() / DAYS_PER_MONTH).setScale(2, RoundingMode.HALF_UP));
        result.setExpectedProfit(campaign.getBudget().multiply(BigDecimal.valueOf(prediction.ratio() - 1.0))
                .setScale(2, RoundingMode.HALF_UP));
        // денежные ответы модели — в BYN, показываем в валюте кампании
        result.setBudgetMin(prediction.budgetMin() == null
                ? null : CurrencyRates.fromBase(campaign.getCurrency(), prediction.budgetMin()));
        result.setBudgetMax(prediction.budgetMax() == null
                ? null : CurrencyRates.fromBase(campaign.getCurrency(), prediction.budgetMax()));
        result.setMape(prediction.dominantModel().getQualityMetric());
    }

    /** Вердикты с числовой оценкой: «ниже минимума площадки» и «вне данных модели» её не дают. */
    static boolean numericVerdict(String verdict) {
        return switch (verdict) {
            case "PROFITABLE", "BORDERLINE", "UNPROFITABLE" -> true;
            default -> false;
        };
    }

    private BigDecimal toRatio(double ratio) {
        return BigDecimal.valueOf(ratio).setScale(4, RoundingMode.HALF_UP);
    }

    private static String verdictLabel(String verdict) {
        return switch (verdict) {
            case "PROFITABLE" -> "вкладываться";
            case "BORDERLINE" -> "на грани";
            case "UNPROFITABLE" -> "убыточно";
            case "OUT_OF_RANGE" -> "вне диапазона данных";
            default -> verdict;
        };
    }

    private ForecastResponse toResponse(Forecast forecast, ForecastParam param, ForecastResult result,
                                        Prediction prediction) {
        return new ForecastResponse(
                forecast.getId(),
                forecast.getStatus().name(),
                forecast.getStartedAt(),
                result.getVerdict(),
                result.getPaybackRatio(),
                result.getForecastRoi(),
                result.getIntervalLower(),
                result.getIntervalUpper(),
                result.getAgreement(),
                result.getPaybackDays(),
                result.getPaybackMonths(),
                result.getPaybackDays() != null && result.getPaybackDays().doubleValue() <= param.getDurationDays(),
                result.getExpectedProfit(),
                result.getBudgetMin(),
                result.getBudgetMax(),
                Boolean.TRUE.equals(result.getBudgetAny()),
                result.getModelScope(),
                result.getDayBudgetMin(),
                result.getDayBudgetMax(),
                prediction.dataNote(),
                param.getDurationDays(),
                param.getStartMonth(),
                param.getHistDays(),
                param.getHistCost(),
                param.getHistRevenue(),
                param.getBudget(),
                param.getCurrency(),
                result.getOwnModel() == null ? null : result.getOwnModel().getModelVersion(),
                forecast.getModel() == null ? null : forecast.getModel().getModelVersion(),
                result.getOwnWeight(),
                prediction.ownCampaigns(),
                prediction.basis(),
                result.getMape()
        );
    }

    private ForecastResponse toHistoryItem(Forecast forecast, ForecastParam param, ForecastResult result) {
        if (param == null || result == null) {
            return new ForecastResponse(
                    forecast.getId(), forecast.getStatus().name(), forecast.getStartedAt(),
                    null, null, null, null, null, null, null, null, null, null, null, null,
                    false, null, null, null, null,
                    null, null, null, null, null, null, null, null,
                    forecast.getModel() == null ? null : forecast.getModel().getModelVersion(),
                    null, null, null, null);
        }
        return new ForecastResponse(
                forecast.getId(),
                forecast.getStatus().name(),
                forecast.getStartedAt(),
                result.getVerdict(),
                result.getPaybackRatio(),
                result.getForecastRoi(),
                result.getIntervalLower(),
                result.getIntervalUpper(),
                result.getAgreement(),
                result.getPaybackDays(),
                result.getPaybackMonths(),
                result.getPaybackDays() != null && result.getPaybackDays().doubleValue() <= param.getDurationDays(),
                result.getExpectedProfit(),
                result.getBudgetMin(),
                result.getBudgetMax(),
                Boolean.TRUE.equals(result.getBudgetAny()),
                result.getModelScope(),
                result.getDayBudgetMin(),
                result.getDayBudgetMax(),
                result.getDataNote(),
                param.getDurationDays(),
                param.getStartMonth(),
                param.getHistDays(),
                param.getHistCost(),
                param.getHistRevenue(),
                param.getBudget(),
                param.getCurrency(),
                result.getOwnModel() == null ? null : result.getOwnModel().getModelVersion(),
                forecast.getModel() == null ? null : forecast.getModel().getModelVersion(),
                result.getOwnWeight(),
                null,
                null,
                result.getMape()
        );
    }
}
