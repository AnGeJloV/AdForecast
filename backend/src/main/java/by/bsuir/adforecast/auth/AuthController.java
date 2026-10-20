package by.bsuir.adforecast.auth;

import by.bsuir.adforecast.domain.User;
import by.bsuir.adforecast.repo.PermissionRepository;
import by.bsuir.adforecast.repo.UserRepository;
import by.bsuir.adforecast.security.JwtService;
import jakarta.validation.Valid;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

    public AuthController(AuthenticationManager authenticationManager,
                          UserRepository userRepository,
                          PermissionRepository permissionRepository,
                          JwtService jwtService) {
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.permissionRepository = permissionRepository;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.login(), request.password()));
        User user = userRepository.findByLogin(request.login()).orElseThrow();
        List<String> permissions = permissionRepository.findByRoleId(user.getRole().getId())
                .stream()
                .map(permission -> permission.getPermissionCode())
                .toList();
        user.setLastLogin(LocalDateTime.now());
        userRepository.save(user);
        String token = jwtService.issue(user.getLogin(), user.getRole().getRoleName(), permissions);
        return new LoginResponse(token, user.getLogin(), user.getRole().getRoleName());
    }

    @GetMapping("/me")
    public CurrentUserResponse me(Authentication authentication) {
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
        return CurrentUserResponse.of(authentication.getName(), role, permissions);
    }
}
