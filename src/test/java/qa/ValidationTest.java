package qa;

import io.qameta.allure.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.util.stream.Stream;

@Epic("Защита от некорректных запросов")
@Feature("Проверка введенных данных")
@Severity(SeverityLevel.NORMAL)
class ValidationTest extends ServiceTest {
    static Stream<Arguments> invalidTokens() {
        Object[][] values = {{"поле отсутствует", null}, {"пустая строка", ""}, {"31 символ", "A".repeat(31)},
                {"33 символа", "A".repeat(33)}, {"один символ", "A"}, {"256 символов", "A".repeat(256)},
                {"строчные буквы", "a".repeat(32)}, {"строчная буква внутри", "A".repeat(15) + "a" + "A".repeat(16)},
                {"пробел", "A".repeat(31) + " "}, {"дефис", "A".repeat(31) + "-"},
                {"подчеркивание", "A".repeat(31) + "_"}, {"кириллица", "А".repeat(32)},
                {"перевод строки", "A".repeat(31) + "\n"}, {"символ плюс", "A".repeat(31) + "+"},
                {"спецсимвол амперсанд", "A".repeat(31) + "&"}};
        // На LOGIN проверяем весь набор. Для ACTION и LOGOUT оставляем отсутствие поля,
        // обе границы длины и неверный алфавит, чтобы не повторять все сочетания.
        Object[][] representatives = {{"поле отсутствует", null}, {"31 символ", "A".repeat(31)},
                {"33 символа", "A".repeat(33)}, {"строчные буквы", "a".repeat(32)}};
        return Stream.concat(
                Stream.of(values).map(v -> Arguments.of("LOGIN", v[0], v[1])),
                Stream.of("ACTION", "LOGOUT").flatMap(action -> Stream.of(representatives)
                        .map(v -> Arguments.of(action, v[0], v[1]))));
    }

    @ParameterizedTest(name = "{0}: {1}") @MethodSource("invalidTokens")
    @DisplayName("Неверный идентификатор пользователя отклоняется без внешних обращений")
    @Story("Отказ при неверном идентификаторе")
    @Description("Для входа проверяем все выбранные ошибки длины и состава идентификатора. Для действия и выхода оставляем отсутствие поля, длину 31 и 33 символа и строчные буквы. Запрос должен отклоняться без внешних обращений.")
    void rejectsToken(String action, String description, String t) {
        error(send(t, action));
        noExternalCalls();
    }

    @ParameterizedTest(name = "Недопустимое действие: [{0}]")
    @NullAndEmptySource @ValueSource(strings = {"UNKNOWN", "login", "action", "logout", "1"})
    @DisplayName("Отсутствующее или неизвестное действие отклоняется")
    @Story("Отказ при неизвестном действии")
    @Description("Проверяем отсутствие действия, неизвестное название и неверный регистр букв. Система должна объяснить отказ и не обращаться к внешним службам.")
    void rejectsAction(String action) {
        error(send(token(), action));
        noExternalCalls();
    }
}
