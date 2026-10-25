package by.bsuir.adforecast.admin;

import by.bsuir.adforecast.domain.MlModel;
import by.bsuir.adforecast.mlclient.MlServiceClient;
import by.bsuir.adforecast.repo.MlModelRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;

@Service
public class AdminSummaryService {

    private static final String COUNTS_SQL = """
            SELECT
                (SELECT count(*) FROM users) AS users_total,
                (SELECT count(*) FROM users WHERE is_active) AS users_active,
                (SELECT count(*) FROM campaigns WHERE status = 'PLANNED') AS campaigns_planned,
                (SELECT count(*) FROM campaigns WHERE status = 'ACTIVE') AS campaigns_active,
                (SELECT count(*) FROM campaigns WHERE status = 'FINISHED') AS campaigns_finished,
                (SELECT count(*) FROM forecasts) AS forecasts_total,
                (SELECT count(*) FROM forecasts WHERE status = 'COMPLETED') AS forecasts_completed,
                (SELECT count(*) FROM uploads) AS uploads_total,
                (SELECT count(*) FROM export_history) AS exports_total
            """;

    private final JdbcTemplate jdbcTemplate;
    private final MlModelRepository mlModelRepository;
    private final MlServiceClient mlServiceClient;

    public AdminSummaryService(JdbcTemplate jdbcTemplate,
                               MlModelRepository mlModelRepository,
                               MlServiceClient mlServiceClient) {
        this.jdbcTemplate = jdbcTemplate;
        this.mlModelRepository = mlModelRepository;
        this.mlServiceClient = mlServiceClient;
    }

    @Transactional(readOnly = true)
    public AdminSummaryResponse summary() {
        Map<String, Object> counts = jdbcTemplate.queryForMap(COUNTS_SQL);
        MlModel active = mlModelRepository.findByOrganizationIdIsNullAndIsActiveTrue().orElse(null);
        boolean mlAvailable;
        try {
            mlServiceClient.models();
            mlAvailable = true;
        } catch (RuntimeException exception) {
            mlAvailable = false;
        }
        return new AdminSummaryResponse(
                ((Number) counts.get("users_total")).longValue(),
                ((Number) counts.get("users_active")).longValue(),
                ((Number) counts.get("campaigns_planned")).longValue(),
                ((Number) counts.get("campaigns_active")).longValue(),
                ((Number) counts.get("campaigns_finished")).longValue(),
                ((Number) counts.get("forecasts_total")).longValue(),
                ((Number) counts.get("forecasts_completed")).longValue(),
                ((Number) counts.get("uploads_total")).longValue(),
                ((Number) counts.get("exports_total")).longValue(),
                // «Версий в реестре» — только общая модель: модели организаций в админке не показываются
                mlModelRepository.countByOrganizationIdIsNull(),
                mlAvailable,
                active == null ? null : active.getModelVersion(),
                active == null ? null : active.getTrainedAt(),
                active == null ? null : active.getQualityMetric()
        );
    }
}
