package by.bsuir.adforecast.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminActivityServiceTest {

    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;

    @InjectMocks
    private AdminActivityService service;

    @Test
    void activityGroupsActionsByMeaningfulCategories() {
        when(jdbcTemplate.queryForList(anyString(), any(MapSqlParameterSource.class))).thenReturn(List.of(
                Map.of("category", "campaigns", "total", 6L),
                Map.of("category", "models", "total", 3L),
                Map.of("category", "users", "total", 15L),
                Map.of("category", "unknown_kind", "total", 2L)));

        AdminActivityResponse response = service.activity(null, null);

        assertThat(response.total()).isEqualTo(26);
        assertThat(response.groups()).extracting(AdminActivityResponse.Group::category,
                AdminActivityResponse.Group::label, AdminActivityResponse.Group::count)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("campaigns", "кампании", 6L),
                        org.assertj.core.groups.Tuple.tuple("models", "модели и обучение", 3L),
                        org.assertj.core.groups.Tuple.tuple("users", "входы и учётные записи", 15L),
                        org.assertj.core.groups.Tuple.tuple("unknown_kind", "прочее", 2L));
    }

    @Test
    void periodsRejectUnknownUnitAndTimeZone() {
        assertThatThrownBy(() -> service.periods("decade", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("неизвестный период");
        assertThatThrownBy(() -> service.periods("day", "Марс/Олимп"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("неизвестный часовой пояс");
    }

    @Test
    void periodsMapsBucketsIntoClientTimeZone() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        OffsetDateTime bucket = OffsetDateTime.parse("2026-10-27T18:00+03:00");
        when(resultSet.getObject("bucket_start", OffsetDateTime.class)).thenReturn(bucket);
        when(resultSet.getObject("total")).thenReturn(4L);
        when(jdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(2);
                    return List.of(mapper.mapRow(resultSet, 0));
                });

        AdminActivityPeriodsResponse response = service.periods("hour", "Europe/Minsk");

        assertThat(response.periods()).hasSize(1);
        assertThat(response.periods().get(0).from().getHour()).isEqualTo(18);
        assertThat(response.periods().get(0).to().getHour()).isEqualTo(19);
        assertThat(response.periods().get(0).count()).isEqualTo(4);
    }
}
