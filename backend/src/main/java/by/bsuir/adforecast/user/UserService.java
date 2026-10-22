package by.bsuir.adforecast.user;

import by.bsuir.adforecast.audit.AuditService;
import by.bsuir.adforecast.domain.Role;
import by.bsuir.adforecast.domain.User;
import by.bsuir.adforecast.repo.RoleRepository;
import by.bsuir.adforecast.repo.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class UserService {

    private static final String MARKETER_ROLE = "маркетолог";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public UserService(UserRepository userRepository,
                       RoleRepository roleRepository,
                       PasswordEncoder passwordEncoder,
                       AuditService auditService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    public List<UserResponse> list() {
        return userRepository.findAllWithRole().stream()
                .map(UserService::toResponse)
                .toList();
    }

    public List<RoleResponse> roles() {
        return roleRepository.findAllByOrderByIdAsc().stream()
                .map(role -> new RoleResponse(role.getId(), role.getRoleName()))
                .toList();
    }

    public UserResponse create(CreateUserRequest request, long actorId) {
        if (userRepository.existsByLogin(request.login())) {
            throw new IllegalArgumentException("логин уже занят");
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new IllegalArgumentException("email уже используется");
        }
        Role role = roleRepository.findById(request.roleId())
                .orElseThrow(() -> new EntityNotFoundException("роль не найдена"));
        User user = new User();
        user.setLogin(request.login());
        user.setEmail(request.email());
        user.setFullName(request.fullName());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(role);
        user.setIsActive(true);
        User saved = userRepository.save(user);
        auditService.record(actorId, "создание пользователя", "пользователь", saved.getId(),
                "логин " + saved.getLogin() + ", роль " + role.getRoleName());
        return toResponse(saved);
    }

    public User register(RegisterRequest request) {
        if (userRepository.existsByLogin(request.login())) {
            throw new IllegalArgumentException("логин уже занят");
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new IllegalArgumentException("email уже используется");
        }
        Role role = roleRepository.findByRoleName(MARKETER_ROLE)
                .orElseThrow(() -> new EntityNotFoundException("роль не найдена"));
        User user = new User();
        user.setLogin(request.login());
        user.setEmail(request.email());
        user.setFullName(request.fullName());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(role);
        user.setIsActive(true);
        User saved = userRepository.save(user);
        auditService.record(saved.getId(), "регистрация", "пользователь", saved.getId(),
                "логин " + saved.getLogin());
        return saved;
    }

    public UserResponse update(long targetId, long actorId, UpdateUserRequest request) {
        User user = userRepository.findByIdWithRole(targetId)
                .orElseThrow(() -> new EntityNotFoundException("пользователь не найден"));
        Role role = roleRepository.findById(request.roleId())
                .orElseThrow(() -> new EntityNotFoundException("роль не найдена"));
        boolean self = targetId == actorId;
        if (self && (!request.isActive() || role.getId() != user.getRole().getId())) {
            throw new IllegalArgumentException("нельзя изменить собственную роль или заблокировать себя");
        }
        if (!request.email().equals(user.getEmail()) && userRepository.existsByEmailAndIdNot(request.email(), user.getId())) {
            throw new IllegalArgumentException("email уже используется");
        }
        user.setEmail(request.email());
        user.setFullName(request.fullName());
        user.setRole(role);
        user.setIsActive(request.isActive());
        User saved = userRepository.save(user);
        auditService.record(actorId, "изменение пользователя", "пользователь", saved.getId(),
                "роль " + role.getRoleName() + (request.isActive() ? "" : ", блокировка"));
        return toResponse(saved);
    }

    private static UserResponse toResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getLogin(),
                user.getEmail(),
                user.getFullName(),
                user.getRole().getRoleName(),
                user.getIsActive(),
                user.getLastLogin()
        );
    }
}
