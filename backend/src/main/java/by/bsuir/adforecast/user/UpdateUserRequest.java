package by.bsuir.adforecast.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateUserRequest(

        @NotBlank
        @Email
        @Size(max = 255)
        String email,

        @NotNull
        Long roleId,

        @NotNull
        Boolean isActive
) {
}
