package by.bsuir.adforecast.auth;

public record LoginResponse(String accessToken, String login, String fullName, String role, String organization) {
}
