package qa;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.LoggedResponse;
import io.qameta.allure.Allure;
import io.restassured.response.Response;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.security.MessageDigest;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

/** Запускает приложение для класса тестов и оформляет общие действия в Allure. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(30)
abstract class ServiceTest {
    static final String KEY = "TEST" + token(); // Один случайный ключ на запуск тестовой JVM; его же получает приложение.
    WireMockServer mock;
    private Process application;
    private Thread shutdownHook;
    private Path log;
    private EndpointClient client;
    private final Map<String, String> users = new LinkedHashMap<>();
    // Связываем конкретный ответ с действием, чтобы в шаге проверки было понятно, что выполнялось.
    private final Map<Response, String> operations = new IdentityHashMap<>();

    @BeforeAll
    @Timeout(75)
    void startApplication() throws Exception {
        Path jar = Path.of(System.getProperty("sut.jar")).toAbsolutePath();
        assertTrue(Files.isRegularFile(jar), "Не найден JAR приложения: " + jar);
        mock = new WireMockServer(wireMockConfig().dynamicPort().bindAddress("127.0.0.1"));
        mock.start();
        log = Path.of("target", "application-" + getClass().getSimpleName() + ".log");
        Files.createDirectories(log.getParent());
        Path results = Path.of(System.getProperty("allure.results.directory", "target/allure-results"));
        Files.createDirectories(results);
        Properties environment = new Properties();
        environment.setProperty("Java", System.getProperty("java.version"));
        environment.setProperty("OS", System.getProperty("os.name"));
        environment.setProperty("Application", jar.getFileName().toString());
        environment.setProperty("SHA256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
        environment.setProperty("Внешняя служба", "Локальный имитатор WireMock, настоящее HTTP-соединение");
        try (var output = Files.newOutputStream(results.resolve("environment.properties"))) {
            environment.store(output, "Test environment");
        }
        try (var input = getClass().getResourceAsStream("/categories.json")) {
            if (input != null) Files.copy(input, results.resolve("categories.json"), StandardCopyOption.REPLACE_EXISTING);
        }
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        try {
            application = new ProcessBuilder(java.toString(), "-Dfile.encoding=UTF-8", "-Dsecret=" + KEY,
                    "-Dmock=http://127.0.0.1:" + mock.port() + "/", "-jar", jar.toString(),
                    "--server.port=0", "--server.address=127.0.0.1")
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            // При обычном завершении JVM убираем дочерний процесс, даже если выполнение тестов прервали.
            shutdownHook = new Thread(this::stopProcess, "stop-test-application");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            // Порт выбирает Spring Boot. Сообщение Tomcat дает и номер порта, и сигнал готовности.
            var portPattern = Pattern.compile("Tomcat started on port(?:\\(s\\))?:? (\\d+)");
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (System.nanoTime() < deadline) {
                assertTrue(application.isAlive(), "Приложение завершилось при запуске:\n" + Files.readString(log));
                var match = portPattern.matcher(Files.readString(log));
                if (match.find()) {
                    client = new EndpointClient(URI.create("http://127.0.0.1:" + match.group(1) + "/endpoint"));
                    return;
                }
                Thread.sleep(100);
            }
            fail("Приложение не запустилось за 60 секунд:\n" + Files.readString(log));
        } catch (Throwable failure) {
            stopApplication();
            throw failure;
        }
    }

    @BeforeEach
    void resetExternalService(TestInfo info) {
        users.clear();
        operations.clear();
        ReportSupport.rememberSecret(KEY);
        var method = info.getTestMethod().orElseThrow();
        String feature = getClass().getAnnotation(io.qameta.allure.Feature.class).value();
        var story = method.getAnnotation(io.qameta.allure.Story.class);
        String title = method.getAnnotation(DisplayName.class).value();
        String variant = info.getDisplayName();
        String reportName = title.equals(variant) ? title : title + " - " + variant;
        reportName = reportName.replace("LOGIN:", "Вход:").replace("ACTION:", "Действие:")
                .replace("LOGOUT:", "Выход:");
        String finalName = reportName;
        Allure.getLifecycle().updateTestCase(result -> {
            result.setName(finalName);
            result.getLabels().removeIf(label -> Set.of("parentSuite", "suite", "subSuite").contains(label.getName()));
            result.getLabels().add(new io.qameta.allure.model.Label().setName("parentSuite").setValue("Проверка пользовательского доступа"));
            result.getLabels().add(new io.qameta.allure.model.Label().setName("suite").setValue(feature));
            if (story != null) result.getLabels().add(new io.qameta.allure.model.Label().setName("subSuite").setValue(story.value()));
        });
        // Настройки и журнал WireMock не должны переходить из одного сценария в другой.
        mock.resetAll();
        mock.stubFor(post(urlEqualTo("/auth")).willReturn(aResponse().withStatus(200).withBody("any response")));
        mock.stubFor(post(urlEqualTo("/doAction")).willReturn(aResponse().withStatus(200).withBody("any response")));
    }

    @AfterEach
    void attachExternalTraffic() {
        StringBuilder traffic = new StringBuilder("Технический журнал после последней очистки счетчика обращений. Значения доступа скрыты.\n\n");
        var events = new ArrayList<>(mock.getAllServeEvents());
        Collections.reverse(events);
        events.forEach(event -> {
            LoggedResponse response = event.getResponse();
            traffic.append(event.getRequest().getMethod()).append(" ").append(event.getRequest().getUrl())
                    .append("\n").append(event.getRequest().getHeaders())
                    .append(event.getRequest().getBodyAsString()).append("\nОтвет: ")
                    .append(response.getStatus()).append("\n\n");
        });
        if (events.isEmpty()) traffic.append("Обращений не было.");
        ReportSupport.attachment("Диагностика: обращения к внешним службам", traffic.toString());
    }

    @AfterAll
    void stopApplication() {
        stopProcess();
        if (shutdownHook != null) {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
            shutdownHook = null;
        }
        if (mock != null) mock.stop();
    }

    private void stopProcess() {
        if (application == null || !application.isAlive()) return;
        application.destroy();
        try {
            if (!application.waitFor(5, TimeUnit.SECONDS)) {
                application.destroyForcibly();
                application.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            application.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    static String token() {
        // UUID дает уникальные токены из A-F и цифр, с которыми можно проверить работу сессии.
        // Остальные разрешенные буквы проверяются отдельно в SessionTest.validToken.
        String value = UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        ReportSupport.rememberSecret(value);
        return value;
    }

    void stub(String path, int status, String body) {
        Allure.step("Подготовить ответ: " + ReportSupport.service(path) + " - " + ReportSupport.httpMeaning(status), step -> {
            step.parameter("Технический код ответа", status);
            mock.stubFor(post(urlEqualTo(path)).willReturn(aResponse().withStatus(status).withBody(body)));
        });
    }

    void clearExternalHistory() {
        // Очищаем только журнал: настроенные ответы внешнего сервиса продолжают действовать.
        Allure.step("Начать отдельный учет внешних обращений для следующего действия", mock::resetRequests);
    }

    Response send(String token, String action) {
        return send(token, action, KEY);
    }

    Response send(String token, String action, String key) {
        ReportSupport.rememberSecret(token);
        if (key != null && key.length() >= 16) ReportSupport.rememberSecret(key);
        String user = users.computeIfAbsent(token, ignored -> "пользователь " + (users.size() + 1));
        String title = ReportSupport.operation(action) + " (" + user + ")";
        return request(key, title, () -> client.send(token, action, key, title));
    }

    Response raw(String method, String body, String key, String contentType) {
        String title = "Обращение с проверяемым форматом данных и способом " + method;
        return request(key, title, () -> client.raw(method, body, key, contentType, title));
    }

    private Response request(String key, String action, java.util.function.Supplier<Response> exchange) {
        if (key != null && key.length() >= 16) ReportSupport.rememberSecret(key);
        return Allure.step(action, step -> {
            step.parameter("Ключ доступа", key == null ? "не передан" : key.isEmpty() ? "пустой" : "передан, значение скрыто");
            Response response = exchange.get();
            operations.put(response, action);
            step.parameter("Ответ системы", responseSummary(response));
            return response;
        });
    }

    String responseSummary(Response response) {
        // Это пояснение для отчета. Решение о прохождении принимает ResponseSpecifications.
        String summary = ReportSupport.httpMeaning(response.statusCode()) + " (HTTP " + response.statusCode() + ")";
        try {
            Object value = response.jsonPath().get();
            if (value instanceof Map<?, ?> json) {
                String result = String.valueOf(json.get("result"));
                summary += switch (result) {
                    case "OK" -> "; операция подтверждена";
                    case "ERROR" -> "; операция отклонена";
                    default -> "; итог операции не указан в согласованном виде";
                };
                if (json.get("message") instanceof String message) summary += "; причина: " + ReportSupport.businessReason(message);
            } else summary += "; ответ не содержит объект с результатом";
        } catch (RuntimeException ignored) {
            summary += "; ответ не удалось прочитать в согласованном формате";
        }
        return ReportSupport.redact(summary);
    }

    void ok(Response response) {
        String action = operations.getOrDefault(response, "операция пользователя");
        String expected = "Операция выполнена; система подтверждает успех и возвращает только результат операции";
        String actual = responseSummary(response);
        ReportSupport.check("Подтвердить успешное выполнение: " + action, expected, actual,
                () -> response.then().spec(ResponseSpecifications.success()));
    }

    void error(Response response) {
        String action = operations.getOrDefault(response, "операция пользователя");
        String expected = "Система отказывает в выполнении операции и сообщает непустую текстовую причину";
        String actual = responseSummary(response);
        ReportSupport.check("Подтвердить отказ с объяснением: " + action, expected, actual,
                () -> response.then().spec(ResponseSpecifications.error()));
    }

    void called(String path, String token, int count) {
        var pattern = postRequestedFor(urlEqualTo(path)).withRequestBody(equalTo("token=" + token));
        int actual = mock.findAll(pattern).size();
        ReportSupport.check("Проверить обращения: " + ReportSupport.service(path),
                "Число обращений с данными нужного пользователя: " + count,
                "Получено подходящих обращений: " + actual, () -> mock.verify(count, pattern));
    }

    void noExternalCalls() {
        int actual = mock.getAllServeEvents().size();
        ReportSupport.check("Убедиться, что внешние службы не вызывались", "Ни одного обращения",
                "Число обращений: " + actual, () -> assertEquals(0, actual, "Запрос не должен обращаться к внешнему сервису"));
    }
}
