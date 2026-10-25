package by.bsuir.adforecast.admin;

import java.time.OffsetDateTime;
import java.util.List;

public record AdminActivityPeriodsResponse(List<Period> periods) {

    public record Period(OffsetDateTime from, OffsetDateTime to, long count) {
    }
}
