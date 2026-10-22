package by.bsuir.adforecast.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(

        @NotBlank
        @Size(min = 3, max = 50)
        @Pattern(regexp = "^[a-zA-Z0-9._-]+$")
        String login,

        @NotBlank
        @Email
        @Size(max = 255)
        String email,

        @NotBlank
        @Size(min = 2, max = 255)
        String fullName,

        @NotBlank
        @Size(min = 6, max = 100)
        String password,

        @NotNull
        Long roleId
) {
}
