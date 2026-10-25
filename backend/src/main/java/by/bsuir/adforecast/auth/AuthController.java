package by.bsuir.adforecast.auth;

import by.bsuir.adforecast.audit.AuditService;
import by.bsuir.adforecast.domain.User;
import by.bsuir.adforecast.repo.PermissionRepository;
import by.bsuir.adforecast.repo.UserRepository;
import by.bsuir.adforecast.security.AuthPrincipal;
import by.bsuir.adforecast.security.JwtService;
import by.bsuir.adforecast.user.RegisterRequest;
import by.bsuir.adforecast.user.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final PermissionRepository permissionRepository;
    private final JwtService jwtService;
    private final UserService userService;
    private final AuditService auditService;

    public AuthController(AuthenticationManager authenticationManager,
                          UserRepository userRepository,
                          PermissionRepository permissionRepository,
                          JwtService jwtService,
                          UserService userService,
                          AuditService auditService) {
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.permissionRepository = permissionRepository;
        this.jwtService = jwtService;
        this.userService = userService;
        this.auditService = auditService;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.login(), request.password()));
        User user = userRepository.findByLogin(request.login()).orElseThrow();
        auditService.record(user.getId(), "вход в систему", "пользователь", user.getId(),
                "логин " + user.getLogin());
        return issueLogin(user);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(Authentication authentication) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        userService.revokeTokens(principal.userId());
        auditService.record(principal.userId(), "выход из системы", "пользователь", principal.userId(),
                "логин " + principal.login());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public LoginResponse register(@Valid @RequestBody RegisterRequest request) {
        User user = userService.register(request);
        return issueLogin(user);
    }

    @GetMapping("/me")
    public CurrentUserResponse me(Authentication authentication) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        User user = userRepository.findByIdWithRole(principal.userId()).orElseThrow();
        String role = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring(5))
                .findFirst()
                .orElse("");
        List<String> permissions = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> !authority.startsWith("ROLE_"))
                .toList();
        return CurrentUserResponse.of(user.getLogin(), user.getFullName(), role,
                user.getOrganization().getName(), permissions);
    }

    private LoginResponse issueLogin(User user) {
        List<String> permissions = permissionRepository.findByRoleId(user.getRole().getId())
                .stream()
                .map(permission -> permission.getPermissionCode())
                .toList();
        user.setLastLogin(LocalDateTime.now());
        userRepository.save(user);
        int tokenVersion = user.getTokenVersion() == null ? 0 : user.getTokenVersion();
        String token = jwtService.issue(user.getId(), user.getLogin(), user.getRole().getRoleName(), permissions,
                tokenVersion);
        return new LoginResponse(token, user.getLogin(), user.getFullName(), user.getRole().getRoleName(),
                user.getOrganization().getName());
    }
}
