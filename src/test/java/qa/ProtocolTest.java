package qa;

import io.qameta.allure.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

@Epic("Защита от некорректных запросов")
@Feature("Понятные отказы при неверном обращении")
@Severity(SeverityLevel.NORMAL)
class ProtocolTest extends ServiceTest {
    @ParameterizedTest(name = "Метод {0}") @ValueSource(strings = {"GET", "PUT", "PATCH", "DELETE"})
    @Tag("http-standard")
    @DisplayName("Неподдерживаемый способ обращения возвращает понятный отказ")
    @Story("Неподдерживаемый способ обращения")
    @Description("Проверяем, что неподдерживаемый способ обращения возвращает понятный отказ, а не сообщение о поломке сервера. Ожидаемый код 405 основан на стандарте HTTP; в задании конкретный код не указан.")
    void wrongMethod(String method) {
        var response = raw(method, "token=" + token() + "&action=LOGIN", KEY, "application/x-www-form-urlencoded");
        ReportSupport.check("Объяснить, что способ обращения не поддерживается",
                "Система сообщает о неподдерживаемом способе обращения (HTTP 405)", responseSummary(response),
                // Собираем независимые нарушения: HTTP-код, тело отказа и лишние внешние вызовы.
                () -> assertAll("Неподдерживаемый HTTP-метод",
                () -> response.then().statusCode(405),
                () -> error(response), this::noExternalCalls));
    }

    @Test @DisplayName("Пустой запрос отклоняется с объяснением причины")
    @Story("Отсутствие обязательных данных")
    @Description("В обращении нет ни идентификатора пользователя, ни действия. Система должна отказать с объяснением причины без внешних обращений.")
    void missingBothFields() {
        error(send(null, null));
        noExternalCalls();
    }

    @Test @DisplayName("Неверный формат переданных данных не позволяет выполнить вход")
    @Story("Неверный способ передачи данных")
    @Description("Данные передаются в другом формате вместо согласованной формы. Система не должна выполнять вход и должна объяснить отказ.")
    void wrongContentType() {
        error(raw("POST", "{\"token\":\"" + token() + "\",\"action\":\"LOGIN\"}", KEY, "application/json"));
        noExternalCalls();
    }
}
