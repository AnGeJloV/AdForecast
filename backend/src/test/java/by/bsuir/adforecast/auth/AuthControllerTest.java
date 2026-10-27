package by.bsuir.adforecast.auth;

import by.bsuir.adforecast.audit.AuditService;
import by.bsuir.adforecast.domain.Organization;
import by.bsuir.adforecast.domain.Permission;
import by.bsuir.adforecast.domain.Role;
import by.bsuir.adforecast.domain.User;
import by.bsuir.adforecast.repo.PermissionRepository;
import by.bsuir.adforecast.repo.UserRepository;
import by.bsuir.adforecast.security.AuthPrincipal;
import by.bsuir.adforecast.security.JwtService;
import by.bsuir.adforecast.user.RegisterRequest;
import by.bsuir.adforecast.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private AuthenticationManager authenticationManager;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PermissionRepository permissionRepository;
    @Mock
    private JwtService jwtService;
    @Mock
    private UserService userService;
    @Mock
    private AuditService auditService;

    @InjectMocks
    private AuthController controller;

    private User marketer;

    @BeforeEach
    void initUser() {
        Role role = new Role();
        role.setId(2L);
        role.setRoleName("маркетолог");
        Organization organization = new Organization();
        organization.setName("Организация «Демо»");
        marketer = new User();
        marketer.setId(5L);
        marketer.setLogin("angejlov");
        marketer.setFullName("Станислав Ангелов");
        marketer.setTokenVersion(0);
        marketer.setRole(role);
        marketer.setOrganization(organization);
    }

    private void stubTokenIssuing() {
        Permission permission = new Permission();
        permission.setPermissionCode("campaign.manage");
        when(permissionRepository.findByRoleId(2L)).thenReturn(List.of(permission));
        when(jwtService.issue(eq(5L), eq("angejlov"), eq("маркетолог"), anyList(), eq(0)))
                .thenReturn("jwt-token");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void loginIssuesTokenWithRoleOrganizationAndAudit() {
        stubTokenIssuing();
        when(userRepository.findByLogin("angejlov")).thenReturn(Optional.of(marketer));

        LoginResponse response = controller.login(new LoginRequest("angejlov", "пароль"));

        assertThat(response.accessToken()).isEqualTo("jwt-token");
        assertThat(response.login()).isEqualTo("angejlov");
        assertThat(response.role()).isEqualTo("маркетолог");
        assertThat(response.organization()).isEqualTo("Организация «Демо»");
        assertThat(marketer.getLastLogin()).isNotNull();
        verify(auditService).record(eq(5L), eq("вход в систему"), eq("пользователь"), eq(5L), anyString());
    }

    @Test
    void failedLoginPropagatesAndWritesNothing() {
        doThrow(new BadCredentialsException("bad"))
                .when(authenticationManager)
                .authenticate(any(UsernamePasswordAuthenticationToken.class));

        assertThatThrownBy(() -> controller.login(new LoginRequest("angejlov", "неверный")))
                .isInstanceOf(BadCredentialsException.class);

        verifyNoInteractions(auditService, jwtService);
    }

    @Test
    void registerReturnsTokenForCreatedUser() {
        when(userService.register(any(RegisterRequest.class))).thenReturn(marketer);
        stubTokenIssuing();

        LoginResponse response = controller.register(
                new RegisterRequest("newuser", "new@example.com", "Имя Фамилия", "пароль123"));

        assertThat(response.accessToken()).isEqualTo("jwt-token");
        assertThat(response.role()).isEqualTo("маркетолог");
        verify(userService).register(any(RegisterRequest.class));
    }

    @Test
    void meReturnsRoleAndPermissionsOfPrincipal() {
        Authentication authentication = mockAuthentication(
                new SimpleGrantedAuthority("ROLE_маркетолог"),
                new SimpleGrantedAuthority("campaign.manage"));
        when(userRepository.findByIdWithRole(5L)).thenReturn(Optional.of(marketer));

        CurrentUserResponse response = controller.me(authentication);

        assertThat(response.login()).isEqualTo("angejlov");
        assertThat(response.fullName()).isEqualTo("Станислав Ангелов");
        assertThat(response.role()).isEqualTo("маркетолог");
        assertThat(response.organization()).isEqualTo("Организация «Демо»");
        assertThat(response.permissions()).containsExactly("campaign.manage");
    }

    @Test
    void logoutRevokesTokensAndRecordsAudit() {
        Authentication authentication = mockAuthentication();

        controller.logout(authentication);

        verify(userService).revokeTokens(5L);
        verify(auditService).record(eq(5L), eq("выход из системы"), eq("пользователь"), eq(5L), anyString());
    }

    private Authentication mockAuthentication(SimpleGrantedAuthority... authorities) {
        Authentication authentication = org.mockito.Mockito.mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(new AuthPrincipal(5L, "angejlov"));
        if (authorities.length > 0) {
            org.mockito.Mockito.doReturn(List.of(authorities)).when(authentication).getAuthorities();
        }
        return authentication;
    }
}
