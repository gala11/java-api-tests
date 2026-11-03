package qa;

import io.qameta.allure.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Epic("Поведение за пределами точных требований")
@Feature("Наблюдения, требующие согласования")
@Severity(SeverityLevel.MINOR)
@Tag("characterization")
// Здесь зафиксировано наблюдаемое поведение. Требования к этим случаям еще нужно согласовать.
class CharacterizationTest extends ServiceTest {
    @Test @DisplayName("Повторный вход отклоняется, действующая сессия сохраняется")
    @Story("Повторный вход")
    @Description("Наблюдение, а не отдельное требование задания: повторный вход отклоняется, внешнего подтверждения повторно нет, прежний доступ сохраняется.")
    void duplicateLogin() {
        String t = token();
        ok(send(t, "LOGIN"));
        error(send(t, "LOGIN"));
        ok(send(t, "ACTION"));
        called("/auth", t, 1);
        called("/doAction", t, 1);
    }

    @Test @DisplayName("Выход без входа возвращает отказ")
    @Story("Выход без входа")
    @Description("Наблюдение, требующее согласования: выход пользователя, который не входил, возвращает отказ без внешних обращений.")
    void unknownLogout() {
        error(send(token(), "LOGOUT"));
        noExternalCalls();
    }

    @Test @DisplayName("Повторный выход возвращает отказ, доступ остается закрытым")
    @Story("Повторный выход")
    @Description("Наблюдение, требующее согласования: после выхода следующий выход возвращает отказ, а действие остается запрещенным.")
    void repeatedLogout() {
        String t = token();
        ok(send(t, "LOGIN"));
        ok(send(t, "LOGOUT"));
        clearExternalHistory();
        error(send(t, "LOGOUT"));
        error(send(t, "ACTION"));
        noExternalCalls();
    }

    @ParameterizedTest(name = "Вход с действием [{0}]") @ValueSource(strings = {" LOGIN", "LOGIN "})
    @DisplayName("Пробелы вокруг названия действия игнорируются")
    @Story("Пробелы в названии действия")
    @Description("Наблюдение, требующее согласования: пробел до или после названия входа игнорируется. Проверяем, что доступ после такого входа работает.")
    void trimsAction(String action) {
        String t = token();
        ok(send(t, action));
        ok(send(t, "ACTION"));
        called("/auth", t, 1);
        called("/doAction", t, 1);
    }

    @ParameterizedTest(name = "Код внешнего сервиса: {0}") @ValueSource(ints = {201, 202, 204, 301, 302, 307})
    @DisplayName("Внешние коды 201/202/204 и 301/302/307 принимаются как успех")
    @Story("Неоговоренные ответы внешней службы")
    @Description("Наблюдение, требующее согласования: проверенные коды 201/202/204 и 301/302/307 принимаются как успех при входе и действии. Перенаправления передаются без адреса назначения; задание не определяет допустимые коды.")
    void otherStatusCodes(int status) {
        // У ответов 3xx нет Location. Сценарий проверяет принятие кода, а не переход по другому адресу.
        String t = token();
        stub("/auth", status, "");
        ok(send(t, "LOGIN"));
        called("/auth", t, 1);
        called("/doAction", t, 0);
        stub("/doAction", status, "");
        ok(send(t, "ACTION"));
        called("/doAction", t, 1);
    }
}
