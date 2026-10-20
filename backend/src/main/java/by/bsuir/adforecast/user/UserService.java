package by.bsuir.adforecast.user;

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

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, RoleRepository roleRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
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

    public UserResponse create(CreateUserRequest request) {
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
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(role);
        user.setIsActive(true);
        return toResponse(userRepository.save(user));
    }

    public UserResponse update(long targetId, long currentUserId, UpdateUserRequest request) {
        User user = userRepository.findByIdWithRole(targetId)
                .orElseThrow(() -> new EntityNotFoundException("пользователь не найден"));
        Role role = roleRepository.findById(request.roleId())
                .orElseThrow(() -> new EntityNotFoundException("роль не найдена"));
        boolean self = targetId == currentUserId;
        if (self && (!request.isActive() || role.getId() != user.getRole().getId())) {
            throw new IllegalArgumentException("нельзя изменить собственную роль или заблокировать себя");
        }
        if (!request.email().equals(user.getEmail()) && userRepository.existsByEmailAndIdNot(request.email(), user.getId())) {
            throw new IllegalArgumentException("email уже используется");
        }
        user.setEmail(request.email());
        user.setRole(role);
        user.setIsActive(request.isActive());
        return toResponse(userRepository.save(user));
    }

    private static UserResponse toResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getLogin(),
                user.getEmail(),
                user.getRole().getRoleName(),
                user.getIsActive(),
                user.getLastLogin()
        );
    }
}
