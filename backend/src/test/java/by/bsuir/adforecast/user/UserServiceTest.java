package by.bsuir.adforecast.user;

import by.bsuir.adforecast.audit.AuditService;
import by.bsuir.adforecast.domain.Organization;
import by.bsuir.adforecast.domain.Role;
import by.bsuir.adforecast.domain.User;
import by.bsuir.adforecast.organization.OrganizationService;
import by.bsuir.adforecast.repo.RoleRepository;
import by.bsuir.adforecast.repo.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private OrganizationService organizationService;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AuditService auditService;

    @InjectMocks
    private UserService service;

    private Role role(long id, String name) {
        Role role = new Role();
        role.setId(id);
        role.setRoleName(name);
        return role;
    }

    private User user(long id, Role role, Organization organization) {
        User user = new User();
        user.setId(id);
        user.setLogin("user" + id);
        user.setEmail("user" + id + "@example.com");
        user.setFullName("Пользователь " + id);
        user.setPasswordHash("hash");
        user.setIsActive(true);
        user.setTokenVersion(0);
        user.setRole(role);
        user.setOrganization(organization);
        return user;
    }

    @Test
    void createEncodesPasswordAndAssignsActorOrganization() {
        Organization organization = new Organization();
        organization.setName("Организация «Демо»");
        when(userRepository.existsByLogin("newuser")).thenReturn(false);
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(roleRepository.findById(2L)).thenReturn(Optional.of(role(2L, "маркетолог")));
        when(userRepository.findById(9L)).thenReturn(Optional.of(user(9L, role(1L, "администратор"), organization)));
        when(passwordEncoder.encode("пароль123")).thenReturn("bcrypt-hash");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(11L);
            return saved;
        });

        UserResponse response = service.create(
                new CreateUserRequest("newuser", "new@example.com", "Имя Фамилия", "пароль123", 2L), 9L);

        assertThat(response.login()).isEqualTo("newuser");
        assertThat(response.role()).isEqualTo("маркетолог");
        assertThat(response.isActive()).isTrue();
        verify(auditService).record(eq(9L), eq("создание пользователя"), eq("пользователь"), eq(11L),
                contains("newuser"));
    }

    @Test
    void createRejectsDuplicateLogin() {
        when(userRepository.existsByLogin("newuser")).thenReturn(true);

        assertThatThrownBy(() -> service.create(
                new CreateUserRequest("newuser", "new@example.com", "Имя Фамилия", "пароль123", 2L), 9L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("логин уже занят");

        verifyNoInteractions(roleRepository, auditService);
    }

    @Test
    void createRejectsDuplicateEmail() {
        when(userRepository.existsByLogin("newuser")).thenReturn(false);
        when(userRepository.existsByEmail("new@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.create(
                new CreateUserRequest("newuser", "new@example.com", "Имя Фамилия", "пароль123", 2L), 9L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("email уже используется");
    }

    @Test
    void createFailsWhenRoleMissing() {
        when(userRepository.existsByLogin("newuser")).thenReturn(false);
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(roleRepository.findById(2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(
                new CreateUserRequest("newuser", "new@example.com", "Имя Фамилия", "пароль123", 2L), 9L))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void registerCreatesNewOrganizationWithStandardAccounts() {
        when(userRepository.existsByLogin("newuser")).thenReturn(false);
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(roleRepository.findByRoleName("маркетолог")).thenReturn(Optional.of(role(2L, "маркетолог")));
        Organization organization = new Organization();
        organization.setName("Организация «Имя Фамилия»");
        when(organizationService.createWithStandardAccounts("Организация «Имя Фамилия»")).thenReturn(organization);
        when(passwordEncoder.encode("пароль123")).thenReturn("bcrypt-hash");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(12L);
            return saved;
        });

        User registered = service.register(
                new RegisterRequest("newuser", "new@example.com", "Имя Фамилия", "пароль123"));

        assertThat(registered.getRole().getRoleName()).isEqualTo("маркетолог");
        assertThat(registered.getOrganization()).isSameAs(organization);
        assertThat(registered.getTokenVersion()).isZero();
        verify(auditService).record(eq(12L), eq("регистрация"), eq("пользователь"), eq(12L), anyString());
    }

    @Test
    void registerRejectsDuplicateLogin() {
        when(userRepository.existsByLogin("newuser")).thenReturn(true);

        assertThatThrownBy(() -> service.register(
                new RegisterRequest("newuser", "new@example.com", "Имя Фамилия", "пароль123")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("логин уже занят");

        verifyNoInteractions(organizationService, auditService);
    }

    @Test
    void updateRejectsSelfBlocking() {
        Role marketerRole = role(2L, "маркетолог");
        when(userRepository.findByIdWithRole(5L)).thenReturn(Optional.of(user(5L, marketerRole, new Organization())));
        when(roleRepository.findById(2L)).thenReturn(Optional.of(marketerRole));

        assertThatThrownBy(() -> service.update(5L, 5L,
                new UpdateUserRequest("user5@example.com", "Пользователь 5", 2L, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("нельзя изменить собственную роль или заблокировать себя");
    }

    @Test
    void updateRejectsSelfRoleChange() {
        Role marketerRole = role(2L, "маркетолог");
        when(userRepository.findByIdWithRole(5L)).thenReturn(Optional.of(user(5L, marketerRole, new Organization())));
        when(roleRepository.findById(1L)).thenReturn(Optional.of(role(1L, "администратор")));

        assertThatThrownBy(() -> service.update(5L, 5L,
                new UpdateUserRequest("user5@example.com", "Пользователь 5", 1L, true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("нельзя изменить собственную роль или заблокировать себя");
    }

    @Test
    void updateRejectsEmailTakenByAnotherUser() {
        Role marketerRole = role(2L, "маркетолог");
        when(userRepository.findByIdWithRole(5L)).thenReturn(Optional.of(user(5L, marketerRole, new Organization())));
        when(roleRepository.findById(2L)).thenReturn(Optional.of(marketerRole));
        when(userRepository.existsByEmailAndIdNot("taken@example.com", 5L)).thenReturn(true);

        assertThatThrownBy(() -> service.update(5L, 9L,
                new UpdateUserRequest("taken@example.com", "Пользователь 5", 2L, true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("email уже используется");
    }

    @Test
    void updateBumpsTokenVersionOnRoleChange() {
        Role marketerRole = role(2L, "маркетолог");
        User target = user(5L, marketerRole, new Organization());
        when(userRepository.findByIdWithRole(5L)).thenReturn(Optional.of(target));
        when(roleRepository.findById(1L)).thenReturn(Optional.of(role(1L, "администратор")));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = service.update(5L, 9L,
                new UpdateUserRequest("user5@example.com", "Пользователь 5", 1L, true));

        assertThat(response.role()).isEqualTo("администратор");
        assertThat(target.getTokenVersion()).isEqualTo(1);
        verify(auditService).record(eq(9L), eq("изменение пользователя"), eq("пользователь"), eq(5L), anyString());
    }

    @Test
    void updateBumpsTokenVersionOnBlocking() {
        Role marketerRole = role(2L, "маркетолог");
        User target = user(5L, marketerRole, new Organization());
        when(userRepository.findByIdWithRole(5L)).thenReturn(Optional.of(target));
        when(roleRepository.findById(2L)).thenReturn(Optional.of(marketerRole));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.update(5L, 9L, new UpdateUserRequest("user5@example.com", "Пользователь 5", 2L, false));

        assertThat(target.getTokenVersion()).isEqualTo(1);
        assertThat(target.getIsActive()).isFalse();
    }

    @Test
    void updateKeepsTokenVersionWithoutSecurityChanges() {
        Role marketerRole = role(2L, "маркетолог");
        User target = user(5L, marketerRole, new Organization());
        when(userRepository.findByIdWithRole(5L)).thenReturn(Optional.of(target));
        when(roleRepository.findById(2L)).thenReturn(Optional.of(marketerRole));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.update(5L, 9L, new UpdateUserRequest("user5@example.com", "Новое имя", 2L, true));

        assertThat(target.getTokenVersion()).isZero();
    }

    @Test
    void revokeTokensBumpsVersion() {
        User target = user(5L, role(2L, "маркетолог"), new Organization());
        when(userRepository.findById(5L)).thenReturn(Optional.of(target));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.revokeTokens(5L);

        assertThat(target.getTokenVersion()).isEqualTo(1);
    }

    @Test
    void listMapsUsersWithRoleNames() {
        when(userRepository.findAllWithRole())
                .thenReturn(List.of(user(5L, role(2L, "маркетолог"), new Organization())));

        List<UserResponse> users = service.list();

        assertThat(users).hasSize(1);
        assertThat(users.get(0).role()).isEqualTo("маркетолог");
    }
}
