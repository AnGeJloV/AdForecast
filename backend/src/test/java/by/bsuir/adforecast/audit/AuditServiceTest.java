package by.bsuir.adforecast.audit;

import by.bsuir.adforecast.domain.AuditAction;
import by.bsuir.adforecast.repo.AuditActionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock
    private AuditActionRepository auditActionRepository;

    @InjectMocks
    private AuditService service;

    @BeforeEach
    void bindRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.10");
        request.addHeader("X-Real-IP", "203.0.113.7");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void resetRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void recordStoresActionWithRealClientIp() {
        service.record(5L, "загрузка показателей", "кампания", 1L, "файл report.csv, строк 30");

        ArgumentCaptor<AuditAction> entries = ArgumentCaptor.forClass(AuditAction.class);
        verify(auditActionRepository).save(entries.capture());
        AuditAction entry = entries.getValue();
        assertThat(entry.getUserId()).isEqualTo(5L);
        assertThat(entry.getAction()).isEqualTo("загрузка показателей");
        assertThat(entry.getObjectType()).isEqualTo("кампания");
        assertThat(entry.getObjectId()).isEqualTo(1L);
        assertThat(entry.getDetails()).isEqualTo("файл report.csv, строк 30");
        assertThat(entry.getIp()).isEqualTo("203.0.113.7");
        assertThat(entry.getActionTime()).isNotNull();
    }

    @Test
    void recordFallsBackToRemoteAddressWithoutProxyHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        service.record(5L, "вход в систему", "пользователь", 5L, "логин admin");

        ArgumentCaptor<AuditAction> entries = ArgumentCaptor.forClass(AuditAction.class);
        verify(auditActionRepository).save(entries.capture());
        assertThat(entries.getValue().getIp()).isEqualTo("10.0.0.5");
    }

    @Test
    void recordWithoutHttpContextStoresNullIp() {
        RequestContextHolder.resetRequestAttributes();

        service.record(5L, "обучение завершено", "модель", 10L, "версия v2");

        ArgumentCaptor<AuditAction> entries = ArgumentCaptor.forClass(AuditAction.class);
        verify(auditActionRepository).save(entries.capture());
        assertThat(entries.getValue().getIp()).isNull();
    }
}
