package by.bsuir.adforecast.user;

import by.bsuir.adforecast.security.AuthPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('user.manage')")
    public List<UserResponse> list() {
        return userService.list();
    }

    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('user.manage')")
    public List<RoleResponse> roles() {
        return userService.roles();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('user.manage')")
    public UserResponse create(@Valid @RequestBody CreateUserRequest request, Authentication authentication) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        return userService.create(request, principal.userId());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('user.manage')")
    public UserResponse update(@PathVariable("id") long id,
                               @Valid @RequestBody UpdateUserRequest request,
                               Authentication authentication) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        return userService.update(id, principal.userId(), request);
    }
}
