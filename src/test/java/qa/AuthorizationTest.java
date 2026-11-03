package qa;

import io.qameta.allure.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.util.stream.Stream;
import static org.hamcrest.Matchers.isIn;

@Epic("Защита доступа")
@Feature("Защита доступа к операциям")
@Severity(SeverityLevel.CRITICAL)
class AuthorizationTest extends ServiceTest {
    static Stream<Arguments> wrongKeys() {
        return Stream.of("LOGIN", "ACTION", "LOGOUT").flatMap(action -> Stream.of(
                Arguments.of(action, "ключ отсутствует", null), Arguments.of(action, "ключ пустой", ""),
                Arguments.of(action, "неверный ключ", "invalid"),
                Arguments.of(action, "изменен регистр", KEY.toLowerCase(java.util.Locale.ROOT))));
    }

    @ParameterizedTest(name = "{0}: {1}") @MethodSource("wrongKeys")
    @DisplayName("Без правильного ключа доступа операция запрещена и состояние пользователя не меняется")
    @Severity(SeverityLevel.CRITICAL)
    @Story("Проверка права доступа в каждом обращении")
    @Description("Каждый запрос должен содержать правильный ключ доступа. Неверный или отсутствующий ключ должен блокировать операцию, не менять состояние пользователя и не вызывать внешние службы.")
    void rejectsKey(String action, String description, String key) {
        String t = token();
        if (!action.equals("LOGIN")) ok(send(t, "LOGIN"));
        clearExternalHistory();
        var response = send(t, action, key);
        ReportSupport.check("Убедиться, что неверный ключ блокирует доступ",
                "Доступ запрещен (код 401 или 403)", responseSummary(response), () ->
                response.then().statusCode(isIn(java.util.List.of(401, 403))));
        // Сначала проверяем последствия отказа для сессии, затем формат ошибки.
        // Так неверный JSON не помешает обнаружить, что запрещенная операция изменила доступ.
        noExternalCalls();
        if (action.equals("LOGIN")) {
            error(send(t, "ACTION"));
            noExternalCalls();
        } else {
            ok(send(t, "ACTION"));
            called("/doAction", t, 1);
        }
        error(response);
    }
}
