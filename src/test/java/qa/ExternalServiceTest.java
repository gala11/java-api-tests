package qa;

import io.qameta.allure.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import com.github.tomakehurst.wiremock.http.Fault;
import static com.github.tomakehurst.wiremock.client.WireMock.*;

@Epic("Связь с внешним сервисом")
@Feature("Работа при ответах и сбоях внешних служб")
@Severity(SeverityLevel.CRITICAL)
class ExternalServiceTest extends ServiceTest {
    @Test @DisplayName("Обрыв связи при входе не открывает доступ; после восстановления можно войти")
    @Story("Восстановление после разрыва связи")
    @Description("Внешняя служба закрывает соединение при входе без ответа. Вход не должен дать доступ. После восстановления связи новый вход и действие должны пройти.")
    void brokenAuthConnection() {
        String t = token();
        Allure.step("Прервать связь со службой подтверждения входа до получения ответа", () ->
                mock.stubFor(post(urlEqualTo("/auth")).willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE))));
        error(send(t, "LOGIN"));
        error(send(t, "ACTION"));
        called("/doAction", t, 0);
        ReportSupport.check("Убедиться, что система действительно обратилась к недоступной службе",
                "Зафиксировано хотя бы одно обращение", "Число обращений: " + mock.getAllServeEvents().size(),
                () -> org.junit.jupiter.api.Assertions.assertFalse(mock.getAllServeEvents().isEmpty(), "Сбой должен быть вызван реальным обращением"));
        stub("/auth", 200, "recovered");
        ok(send(t, "LOGIN"));
        ok(send(t, "ACTION"));
    }

    @Test @DisplayName("После обрыва связи можно продолжить работу без нового входа")
    @Story("Продолжение работы после сбоя связи")
    @Description("После входа связь обрывается во время действия. Система должна сообщить об отказе и разрешить действие после восстановления связи без нового входа.")
    void brokenActionConnection() {
        String t = token();
        ok(send(t, "LOGIN"));
        clearExternalHistory();
        Allure.step("Прервать связь со службой выполнения действий до получения ответа", () ->
                mock.stubFor(post(urlEqualTo("/doAction")).willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE))));
        error(send(t, "ACTION"));
        ReportSupport.check("Убедиться, что система действительно обратилась к недоступной службе",
                "Зафиксировано хотя бы одно обращение", "Число обращений: " + mock.getAllServeEvents().size(),
                () -> org.junit.jupiter.api.Assertions.assertFalse(mock.getAllServeEvents().isEmpty(), "Сбой должен быть вызван реальным обращением"));
        stub("/doAction", 200, "recovered");
        ok(send(t, "ACTION"));
    }

    @ParameterizedTest(name = "Вариант текста ответа {index}")
    @ValueSource(strings = {"", "not JSON", "{\"result\":\"ERROR\"}", "<html>text</html>", "Произвольный ответ"})
    @DisplayName("При подтвержденном успехе внешней службы произвольный текст ответа не мешает работе")
    @Story("Решение по результату внешней операции")
    @Description("Внешняя служба подтверждает успех кодом 200, но присылает разные тексты, включая сообщение ERROR. Вход и действие должны быть успешными: по заданию содержимое ответа не влияет на решение.")
    void ignoresBody(String body) {
        Allure.parameter("Содержимое внешнего ответа", body.isEmpty() ? "пустой ответ"
                : body.startsWith("{") ? "объект с текстом ERROR"
                : body.startsWith("<") ? "страница HTML" : "произвольный текст");
        String t = token();
        stub("/auth", 200, body);
        stub("/doAction", 200, body);
        ok(send(t, "LOGIN"));
        ok(send(t, "ACTION"));
        called("/auth", t, 1);
        called("/doAction", t, 1);
    }

    @ParameterizedTest(name = "Внешняя авторизация вернула HTTP {0}")
    @ValueSource(ints = {400, 503})
    @DisplayName("Ошибка входа не создает сессию, после восстановления можно войти")
    @Story("Восстановление после отказа во входе")
    @Description("Внешняя служба отказывает во входе. Проверяем отсутствие доступа после отказа, затем восстанавливаем службу и проверяем новый вход и действие.")
    void authFailure(int status) {
        // 400 и 503 представляют классы 4xx и 5xx. После отказа вход не должен создать сессию.
        String t = token();
        stub("/auth", status, "arbitrary upstream error");
        error(send(t, "LOGIN"));
        error(send(t, "ACTION"));
        called("/auth", t, 1);
        called("/doAction", t, 0);
        stub("/auth", 200, "recovered");
        ok(send(t, "LOGIN"));
        ok(send(t, "ACTION"));
    }

    @ParameterizedTest(name = "Внешнее действие вернуло HTTP {0}")
    @ValueSource(ints = {400, 503})
    @DisplayName("Ошибка действия возвращает отказ, после восстановления сессия работает")
    @Story("Восстановление после отказа в действии")
    @Description("После успешного входа внешняя служба возвращает ошибку действия. Проверяем отказ и возможность повторить действие после восстановления без нового входа.")
    void actionFailure(int status) {
        // Сохранение сессии после сбоя ACTION - допущение: задание связывает удаление токена с LOGOUT.
        String t = token();
        ok(send(t, "LOGIN"));
        stub("/doAction", status, "arbitrary upstream error");
        error(send(t, "ACTION"));
        stub("/doAction", 200, "recovered");
        ok(send(t, "ACTION"));
        called("/auth", t, 1);
        called("/doAction", t, 2);
    }

    @Test @DisplayName("Внешние службы получают данные нужного пользователя в согласованном формате")
    @Story("Правильная передача данных пользователя")
    @Description("Проверяем, что при входе и действии внешняя служба получает ровно одно обращение с правильным идентификатором и согласованным способом передачи данных. Сами значения доступа в отчете скрыты.")
    void requestContract() {
        // Ответ приложения не доказывает, что наружу ушли верные данные. Проверяем журнал WireMock.
        String t = token();
        ok(send(t, "LOGIN"));
        ok(send(t, "ACTION"));
        for (String path : new String[]{"/auth", "/doAction"}) {
            var pattern = postRequestedFor(urlEqualTo(path))
                            .withHeader("Content-Type", matching("(?i)application/x-www-form-urlencoded(?:;.*)?"))
                            .withHeader("Accept", containing("application/json"))
                            .withRequestBody(equalTo("token=" + t));
            ReportSupport.check("Подтвердить правильную передачу данных: " + ReportSupport.service(path),
                    "Одно обращение с данными нужного пользователя в согласованном формате",
                    "Подходящих обращений: " + mock.findAll(pattern).size(), () -> mock.verify(1, pattern));
        }
    }

    @Test @DisplayName("Выход не зависит от доступности внешнего сервиса")
    @Story("Выход при недоступности внешних служб")
    @Description("Вошедший пользователь должен иметь возможность выйти даже при отказах внешних служб. Выход закрывает доступ и не требует внешних обращений.")
    void logoutWithoutDependency() {
        String t = token();
        ok(send(t, "LOGIN"));
        clearExternalHistory();
        stub("/auth", 503, "unavailable");
        stub("/doAction", 503, "unavailable");
        ok(send(t, "LOGOUT"));
        error(send(t, "ACTION"));
        noExternalCalls();
    }
}
