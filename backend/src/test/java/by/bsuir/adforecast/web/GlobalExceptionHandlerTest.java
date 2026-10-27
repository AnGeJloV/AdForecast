package by.bsuir.adforecast.web;

import by.bsuir.adforecast.mlclient.MlServiceException;
import by.bsuir.adforecast.upload.UploadException;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.core.MethodParameter;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void badCredentialsIsUnauthorized() {
        assertThat(handler.badCredentials())
                .extracting(GlobalExceptionHandler.ErrorResponse::message)
                .isEqualTo("неверный логин или пароль");
    }

    @Test
    void blockedAccountIsUnauthorized() {
        assertThat(handler.disabled().message()).isEqualTo("учетная запись заблокирована");
    }

    @Test
    void accessDeniedIsForbidden() {
        assertThat(handler.accessDenied().message()).isEqualTo("нет доступа к операции");
    }

    @Test
    void entityNotFoundKeepsOriginalMessage() {
        assertThat(handler.notFound(new EntityNotFoundException("кампания не найдена")).message())
                .isEqualTo("кампания не найдена");
    }

    @Test
    void uploadExceptionReturnsRowLevelErrors() {
        GlobalExceptionHandler.ValidationErrorResponse response = handler.upload(
                new UploadException(List.of("строка 3: некорректная дата")));

        assertThat(response.message()).isEqualTo("файл не прошел проверку");
        assertThat(response.errors()).containsExactly("строка 3: некорректная дата");
    }

    @Test
    void mlOutageIsServiceUnavailable() {
        assertThat(handler.mlUnavailable(new MlServiceException("сервис прогнозирования недоступен")).message())
                .isEqualTo("сервис прогнозирования недоступен");
    }

    @Test
    void illegalArgumentIsBadRequestAndIllegalStateIsConflict() {
        assertThat(handler.badArgument(new IllegalArgumentException("укажите длительность")).message())
                .isEqualTo("укажите длительность");
        assertThat(handler.conflict(new IllegalStateException("экспорт невозможен")).message())
                .isEqualTo("экспорт невозможен");
    }

    @Test
    void sizeAndBodyLimitsAreBadRequest() {
        assertThat(handler.tooLarge().message()).contains("10 МБ");
        assertThat(handler.unreadable().message()).contains("некорректное тело запроса");
    }

    @Test
    void validationErrorsListFieldAndMessage() throws Exception {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "budget", "должно быть положительным"));
        MethodParameter parameter = new MethodParameter(methodWithBodyParam(), 0);
        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(parameter, bindingResult);

        GlobalExceptionHandler.ErrorResponse response = handler.validation(exception);

        assertThat(response.message()).isEqualTo("budget: должно быть положительным");
    }

    @Test
    void unexpectedErrorsDoNotLeakDetails() {
        GlobalExceptionHandler.ErrorResponse response =
                handler.unexpected(new RuntimeException("секретная деталь"));

        assertThat(response.message()).isEqualTo("внутренняя ошибка сервера");
    }

    private Method methodWithBodyParam() throws NoSuchMethodException {
        return getClass().getDeclaredMethod("requestHolder", String.class);
    }

    @SuppressWarnings("unused")
    private static void requestHolder(String body) {
    }
}
