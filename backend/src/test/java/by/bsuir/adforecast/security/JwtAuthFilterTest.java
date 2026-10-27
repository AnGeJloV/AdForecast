package by.bsuir.adforecast.security;

import by.bsuir.adforecast.domain.Organization;
import by.bsuir.adforecast.domain.Permission;
import by.bsuir.adforecast.domain.Role;
import by.bsuir.adforecast.domain.User;
import by.bsuir.adforecast.repo.PermissionRepository;
import by.bsuir.adforecast.repo.UserRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtAuthFilterTest {

    private static final String SECRET =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private final JwtService jwtService = new JwtService(SECRET, Duration.ofHours(8));
    private final UserRepository userRepository = mock(UserRepository.class);
    private final PermissionRepository permissionRepository = mock(PermissionRepository.class);
    private final JwtAuthFilter filter = new JwtAuthFilter(jwtService, userRepository, permissionRepository);
    private final FilterChain chain = mock(FilterChain.class);

    private User user;

    @BeforeEach
    void initUser() {
        Role role = new Role();
        role.setId(1L);
        role.setRoleName("маркетолог");
        Organization organization = new Organization();
        organization.setName("Организация «Тест»");
        user = new User();
        user.setId(5L);
        user.setLogin("marketer");
        user.setIsActive(true);
        user.setTokenVersion(0);
        user.setRole(role);
        user.setOrganization(organization);

        Permission permission = new Permission();
        permission.setPermissionCode("campaign.manage");

        lenient().when(userRepository.findByLogin("marketer")).thenReturn(Optional.of(user));
        lenient().when(permissionRepository.findByRoleId(1L)).thenReturn(List.of(permission));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void passFilter(String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (token != null) {
            request.addHeader("Authorization", "Bearer " + token);
        }
        filter.doFilter(request, new MockHttpServletResponse(), chain);
    }

    @Test
    void validTokenAuthenticatesUserWithRoleAndPermissions() throws Exception {
        String token = jwtService.issue(5L, "marketer", "маркетолог", List.of("campaign.manage"), 0);

        passFilter(token);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getPrincipal()).isEqualTo(new AuthPrincipal(5L, "marketer"));
        assertThat(authentication.getAuthorities())
                .extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_маркетолог", "campaign.manage");
    }

    @Test
    void requestWithoutTokenLeavesContextEmpty() throws Exception {
        passFilter(null);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void unknownUserIsNotAuthenticated() throws Exception {
        when(userRepository.findByLogin("ghost")).thenReturn(Optional.empty());
        String token = jwtService.issue(99L, "ghost", "маркетолог", List.of(), 0);

        passFilter(token);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void blockedUserIsNotAuthenticated() throws Exception {
        user.setIsActive(false);
        String token = jwtService.issue(5L, "marketer", "маркетолог", List.of(), 0);

        passFilter(token);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void staleTokenVersionIsNotAuthenticated() throws Exception {
        user.setTokenVersion(1);
        String token = jwtService.issue(5L, "marketer", "маркетолог", List.of(), 0);

        passFilter(token);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void brokenTokenIsNotAuthenticated() throws Exception {
        passFilter("поврежденный-токен");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
