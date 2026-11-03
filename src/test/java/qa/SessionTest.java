package qa;

import io.qameta.allure.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

@Epic("Работа пользователя с сервисом")
@Feature("Вход и завершение работы")
@Severity(SeverityLevel.CRITICAL)
class SessionTest extends ServiceTest {
    @Test @DisplayName("Полный путь: вход, действие, выход и отказ после выхода")
    @Story("Полный путь пользователя")
    @Description("Проверяем, что вход дает возможность выполнять действия, а выход прекращает доступ. После выхода новая попытка действия должна получить отказ.")
    void lifecycle() {
        String t = token();
        ok(send(t, "LOGIN"));
        ok(send(t, "ACTION"));
        ok(send(t, "LOGOUT"));
        error(send(t, "ACTION"));
        called("/auth", t, 1);
        called("/doAction", t, 1);
        ReportSupport.check("Подтвердить, что выход и последующий отказ не вызвали внешние службы",
                "Всего два обращения: для входа и разрешенного действия",
                "Всего обращений: " + mock.getAllServeEvents().size(), () ->
                        assertEquals(2, mock.getAllServeEvents().size(), "Выход и отказ не вызывают внешний сервис"));
    }

    @Test @DisplayName("Действие без предварительного входа запрещено")
    @Story("Доступ только после входа")
    @Description("Проверяем защиту от действий без входа. Система должна отказать и не обращаться к внешним службам.")
    void actionWithoutLogin() {
        error(send(token(), "ACTION"));
        noExternalCalls();
    }

    @Test @DisplayName("После одного входа можно выполнить несколько действий")
    @Story("Продолжение работы после входа")
    @Description("Один вход должен разрешать несколько последовательных действий без повторного подтверждения личности.")
    void repeatedActions() {
        String t = token();
        ok(send(t, "LOGIN"));
        for (int i = 0; i < 3; i++) ok(send(t, "ACTION"));
        called("/auth", t, 1);
        called("/doAction", t, 3);
    }

    @Test @DisplayName("После выхода можно снова войти с прежними данными")
    @Story("Возвращение после выхода")
    @Description("Пользователь завершает работу и входит снова с прежним идентификатором. Новый вход должен восстановить возможность действия.")
    void loginAgain() {
        String t = token();
        ok(send(t, "LOGIN"));
        ok(send(t, "LOGOUT"));
        ok(send(t, "LOGIN"));
        ok(send(t, "ACTION"));
        called("/auth", t, 2);
        called("/doAction", t, 1);
    }

    @Test @DisplayName("Выход одного пользователя не закрывает доступ другому")
    @Story("Независимость пользователей")
    @Description("Проверяем, что вход и выход одного пользователя не открывают и не закрывают доступ другому.")
    void independentSessions() {
        String a = token(), b = token();
        ok(send(a, "LOGIN"));
        error(send(b, "ACTION"));
        ok(send(b, "LOGIN"));
        // После выхода первого пользователя проверяем обоих: один потерял доступ, второй сохранил.
        ok(send(a, "LOGOUT"));
        error(send(a, "ACTION"));
        ok(send(b, "ACTION"));
        called("/doAction", a, 0);
        called("/doAction", b, 1);
    }

    // Границы A-Z и 0-9 дополняет смешанный токен. Z и смешанный набор выявляют ограничение до A-F.
    @ParameterizedTest(name = "Набор допустимых данных {index}")
    @ValueSource(strings = {"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ",
            "00000000000000000000000000000000", "99999999999999999999999999999999",
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"})
    @DisplayName("Разрешенные заданием буквы и цифры позволяют войти и работать")
    @Story("Допустимые данные для входа")
    @Description("Проверяем разрешенный заданием идентификатор: 32 заглавные латинские буквы или цифры. С такими данными должны работать вход, действие и выход.")
    void validToken(String t) {
        Allure.parameter("Вид идентификатора", t.chars().distinct().count() == 1
                ? "32 повторения символа " + t.charAt(0) : "32 символа: разные заглавные латинские буквы и цифры");
        ok(send(t, "LOGIN"));
        ok(send(t, "ACTION"));
        ok(send(t, "LOGOUT"));
        called("/auth", t, 1);
        called("/doAction", t, 1);
    }
}
