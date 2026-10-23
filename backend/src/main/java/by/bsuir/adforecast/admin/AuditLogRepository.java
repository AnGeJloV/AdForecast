package by.bsuir.adforecast.admin;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Нативный запрос: время ищется в формате интерфейса, колонка сортировки — из белого списка. */
@Repository
public class AuditLogRepository {

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "time", "a.action_time",
            "user", "coalesce(u.full_name, u.login, '')",
            "action", "a.action",
            "object", "coalesce(a.object_type, '')",
            "details", "coalesce(a.details, '')",
            "ip", "coalesce(a.ip, '')");

    private static final String SELECT = """
            select a.log_id, a.user_id, u.login, u.full_name, a.action, a.object_type,
                   a.object_id, a.details, a.action_time, a.ip
            from audit_actions a
            left join users u on u.user_id = a.user_id
            """;

    private static final String WHERE = """
            where (:category = 'all'
                   or (:category = 'users' and a.action in ('регистрация', 'вход в систему', 'выход из системы', 'создание пользователя', 'изменение пользователя'))
                   or (:category = 'data' and a.action like 'загрузка%')
                   or (:category = 'models' and a.action in ('запуск обучения', 'обучение завершено', 'активация версии модели', 'изменение флагов обучения'))
                   or (:category = 'backups' and a.action like '%копи%'))
              and (lower(coalesce(u.login, '')) like :pattern escape '\\'
                   or lower(coalesce(u.full_name, '')) like :pattern escape '\\'
                   or lower(a.action) like :pattern escape '\\'
                   or lower(coalesce(a.object_type, '')) like :pattern escape '\\'
                   or lower(coalesce(a.details, '')) like :pattern escape '\\'
                   or lower(coalesce(a.ip, '')) like :pattern escape '\\'
                   or to_char(a.action_time, 'DD.MM.YYYY HH24:MI') like :pattern escape '\\')
            """;

    private final EntityManager entityManager;

    public AuditLogRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public Page<AuditEntryResponse> search(String category, String pattern, String sort, String direction, int page, int size) {
        String column = SORT_COLUMNS.getOrDefault(sort, "a.action_time");
        String order = "asc".equalsIgnoreCase(direction) ? "asc" : "desc";

        Query query = entityManager.createNativeQuery(
                SELECT + WHERE + " order by " + column + " " + order + ", a.log_id desc limit :limit offset :offset");
        query.setParameter("category", category);
        query.setParameter("pattern", pattern);
        query.setParameter("limit", size);
        query.setParameter("offset", (long) page * size);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<AuditEntryResponse> entries = rows.stream().map(AuditLogRepository::toEntry).toList();

        Query count = entityManager.createNativeQuery(
                "select count(*) from audit_actions a left join users u on u.user_id = a.user_id " + WHERE);
        count.setParameter("category", category);
        count.setParameter("pattern", pattern);
        long total = ((Number) count.getSingleResult()).longValue();

        return new PageImpl<>(entries, PageRequest.of(page, size), total);
    }

    private static AuditEntryResponse toEntry(Object[] row) {
        return new AuditEntryResponse(
                number(row[0]),
                row[1] == null ? null : number(row[1]),
                (String) row[2],
                (String) row[3],
                (String) row[4],
                (String) row[5],
                row[6] == null ? null : number(row[6]),
                (String) row[7],
                time(row[8]),
                (String) row[9]);
    }

    private static Long number(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static LocalDateTime time(Object value) {
        if (value instanceof LocalDateTime local) {
            return local;
        }
        if (value instanceof Timestamp stamp) {
            return stamp.toLocalDateTime();
        }
        if (value instanceof OffsetDateTime offset) {
            return offset.toLocalDateTime();
        }
        return null;
    }
}
