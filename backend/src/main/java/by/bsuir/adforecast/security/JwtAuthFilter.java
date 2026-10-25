package by.bsuir.adforecast.security;

import by.bsuir.adforecast.domain.User;
import by.bsuir.adforecast.repo.PermissionRepository;
import by.bsuir.adforecast.repo.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final PermissionRepository permissionRepository;

    public JwtAuthFilter(JwtService jwtService,
                         UserRepository userRepository,
                         PermissionRepository permissionRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.permissionRepository = permissionRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims claims = jwtService.parse(header.substring(7));
                User user = userRepository.findByLogin(claims.getSubject()).orElse(null);
                if (user == null || !Boolean.TRUE.equals(user.getIsActive()) || versionChanged(claims, user)) {
                    SecurityContextHolder.clearContext();
                } else {
                    List<GrantedAuthority> authorities = new ArrayList<>();
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + user.getRole().getRoleName()));
                    permissionRepository.findByRoleId(user.getRole().getId())
                            .forEach(permission -> authorities.add(
                                    new SimpleGrantedAuthority(permission.getPermissionCode())));
                    AuthPrincipal principal = new AuthPrincipal(user.getId(), user.getLogin());
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(principal, null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (JwtException | IllegalArgumentException ignored) {
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }

    /** Выход, блокировка и смена роли увеличивают версию токена и отзывают старые JWT. */
    private static boolean versionChanged(Claims claims, User user) {
        Object rawVersion = claims.get("ver");
        int tokenVersion = rawVersion instanceof Number number ? number.intValue() : 0;
        int currentVersion = user.getTokenVersion() == null ? 0 : user.getTokenVersion();
        return tokenVersion != currentVersion;
    }
}
