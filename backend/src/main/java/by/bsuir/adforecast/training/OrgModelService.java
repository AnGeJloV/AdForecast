package by.bsuir.adforecast.training;

import by.bsuir.adforecast.domain.MlModel;
import by.bsuir.adforecast.domain.Organization;
import by.bsuir.adforecast.mlclient.MlServiceException;
import by.bsuir.adforecast.mlclient.TrainAcceptedResponse;
import by.bsuir.adforecast.mlclient.TrainStatusResponse;
import by.bsuir.adforecast.mlclient.MlServiceClient;
import by.bsuir.adforecast.repo.MlModelRepository;
import by.bsuir.adforecast.repo.OrganizationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Модели организаций обучаются сами: администратор ими не управляет, как и не должен
 * при десятках организаций. Проверка запускается по расписанию и при запросе прогноза,
 * переобучение происходит только когда в организации появились новые завершённые
 * кампании (сравнение подписи данных), не чаще минимального интервала.
 */
@Service
public class OrgModelService {

    private static final Logger log = LoggerFactory.getLogger(OrgModelService.class);

    private static final Duration MIN_RETRAIN_INTERVAL = Duration.ofMinutes(30);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(5);
    private static final Duration TRAINING_TIMEOUT = Duration.ofMinutes(15);

    private final OrganizationRepository organizationRepository;
    private final MlModelRepository mlModelRepository;
    private final TrainingService trainingService;
    private final MlServiceClient mlServiceClient;

    private final Set<Long> inProgress = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "org-model-trainer");
        thread.setDaemon(true);
        return thread;
    });

    public OrgModelService(OrganizationRepository organizationRepository,
                           MlModelRepository mlModelRepository,
                           TrainingService trainingService,
                           MlServiceClient mlServiceClient) {
        this.organizationRepository = organizationRepository;
        this.mlModelRepository = mlModelRepository;
        this.trainingService = trainingService;
        this.mlServiceClient = mlServiceClient;
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 1_800_000)
    public void sweep() {
        executor.submit(this::ensureGeneralModel);
        organizationRepository.findAll().forEach(organization -> triggerIfStale(organization.getId()));
    }

    /** Не блокирует вызывающий поток: проверка и обучение идут в фоне. */
    public void triggerIfStale(long organizationId) {
        executor.submit(() -> {
            ensureGeneralModel();
            if (!inProgress.add(organizationId)) {
                return;
            }
            try {
                refresh(organizationId);
            } catch (MlServiceException exception) {
                log.warn("модель организации {} не обновлена: {}", organizationId, exception.getMessage());
            } catch (RuntimeException exception) {
                log.error("ошибка автоматического обучения модели организации {}", organizationId, exception);
            } finally {
                inProgress.remove(organizationId);
            }
        });
    }

    /** Первый запуск на чистой системе: обучаем общую модель, чтобы прогнозы вообще работали.
     *  Дальнейшее переобучение общей модели остаётся за администратором. */
    private void ensureGeneralModel() {
        if (!mlModelRepository.findByOrganizationIdIsNull().isEmpty()) {
            return;
        }
        TrainingService.TrainingPayload payload;
        try {
            payload = trainingService.buildPayload(null);
        } catch (IllegalArgumentException exception) {
            return;
        }
        TrainAcceptedResponse accepted = trainingService.submit(payload, null, null);
        log.info("нет ни одной модели — запущено начальное обучение общей модели ({} кампаний)",
                payload.campaignCount());
        TrainStatusResponse status = awaitCompletion(accepted.taskId());
        if ("COMPLETED".equals(status.status()) && status.model() != null) {
            trainingService.registerModel(null, status.model(), payload.signature());
            log.info("общая модель обучена автоматически: {}", status.model().version());
        }
    }

    private void refresh(long organizationId) {
        Organization organization = organizationRepository.findById(organizationId).orElse(null);
        if (organization == null) {
            return;
        }
        MlModel active = mlModelRepository.findByOrganizationIdAndIsActiveTrue(organizationId).orElse(null);
        String signature = trainingService.datasetSignature(organization);
        if (active != null) {
            if (signature.equals(active.getTrainingSignature())) {
                return;
            }
            if (active.getTrainedAt().isAfter(LocalDateTime.now().minus(MIN_RETRAIN_INTERVAL))) {
                return;
            }
        }

        TrainingService.TrainingPayload payload;
        try {
            payload = trainingService.buildPayload(organization);
        } catch (IllegalArgumentException exception) {
            return;
        }
        TrainAcceptedResponse accepted = trainingService.submit(payload, null, null);
        log.info("запущено автоматическое обучение модели организации {} ({}, кампаний {})",
                organizationId, payload.modelName(), payload.campaignCount());

        TrainStatusResponse status = awaitCompletion(accepted.taskId());
        if ("COMPLETED".equals(status.status()) && status.model() != null) {
            trainingService.registerModel(null, status.model(), payload.signature());
            log.info("модель организации {} обновлена до {}", organizationId, status.model().version());
        } else {
            log.warn("автоматическое обучение модели организации {} не завершилось: {}", organizationId, status.status());
        }
    }

    private TrainStatusResponse awaitCompletion(String taskId) {
        long deadline = System.currentTimeMillis() + TRAINING_TIMEOUT.toMillis();
        for (;;) {
            TrainStatusResponse status = mlServiceClient.trainStatus(taskId);
            if (!"RUNNING".equals(status.status()) || System.currentTimeMillis() > deadline) {
                return status;
            }
            try {
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return status;
            }
        }
    }
}
