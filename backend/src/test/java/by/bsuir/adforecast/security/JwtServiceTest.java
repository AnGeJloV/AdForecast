package by.bsuir.adforecast.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private final JwtService service = new JwtService(SECRET, Duration.ofHours(8));

    @Test
    void issuedTokenParsesBackWithAllClaims() {
        String token = service.issue(7L, "marketer", "маркетолог", List.of("campaign.manage"), 3);

        Claims claims = service.parse(token);

        assertThat(claims.getSubject()).isEqualTo("marketer");
        assertThat(((Number) claims.get("uid")).longValue()).isEqualTo(7L);
        assertThat(claims.get("role", String.class)).isEqualTo("маркетолог");
        assertThat(claims.get("perms", List.class)).containsExactly("campaign.manage");
        assertThat(((Number) claims.get("ver")).intValue()).isEqualTo(3);
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        String token = service.issue(1L, "admin", "администратор", List.of(), 0);
        JwtService stranger = new JwtService(
                "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", Duration.ofHours(8));

        assertThatThrownBy(() -> stranger.parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void expiredTokenIsRejected() {
        JwtService shortLived = new JwtService(SECRET, Duration.ofSeconds(-1));
        String token = shortLived.issue(1L, "admin", "администратор", List.of(), 0);

        assertThatThrownBy(() -> shortLived.parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void garbageStringIsRejected() {
        assertThatThrownBy(() -> service.parse("не-токен")).isInstanceOf(JwtException.class);
    }
}
