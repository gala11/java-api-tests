package qa;

import io.qameta.allure.Allure;
import org.junit.jupiter.api.function.Executable;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Готовит текст отчета и скрывает значения доступа. Условия проверок задают сами тесты. */
final class ReportSupport {
    private static final Set<String> SECRETS = ConcurrentHashMap.newKeySet();
    private static final Pattern JSON_SECRET = Pattern.compile(
            "(?i)(\"(?:token|password|secret|api[-_]?key|authorization|email|phone)\"\\s*:\\s*)\"(?:\\\\.|[^\"\\\\])*\"");
    private static final Pattern FORM_SECRET = Pattern.compile(
            "(?i)((?:^|[?&\\s])(?:token|password|secret|api[-_]?key)=)[^&\\s]*");
    private static final Pattern HEADER_SECRET = Pattern.compile(
            "(?im)^((?:X-Api-Key|Authorization|Cookie|Set-Cookie)\\s*[:=]\\s*).*$");

    private ReportSupport() { }

    static void rememberSecret(String value) {
        if (value != null && value.length() >= 6) {
            SECRETS.add(value);
            SECRETS.add(URLEncoder.encode(value, StandardCharsets.UTF_8));
        }
    }

    static String redact(String text) {
        if (text == null) return null;
        String safe = JSON_SECRET.matcher(text).replaceAll("$1\"[скрыто]\"");
        safe = FORM_SECRET.matcher(safe).replaceAll("$1[скрыто]");
        safe = HEADER_SECRET.matcher(safe).replaceAll("$1[скрыто]");
        // Значение может встретиться в исключении или сравнении запросов WireMock без имени поля.
        // Сначала заменяем длинные значения, чтобы короткое совпадение не оставило часть секрета.
        for (String secret : SECRETS.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
            safe = safe.replace(secret, "[скрыто]");
        }
        return safe;
    }

    static void attachment(String title, String text) {
        Allure.addAttachment(title, "text/plain", redact(text), ".txt");
    }

    static void check(String title, String expected, String actual, Executable assertion) {
        Allure.step(title, step -> {
            step.parameter("Ожидаемый результат", redact(expected));
            step.parameter("Фактический результат", redact(actual));
            assertion.execute();
        });
    }

    static String operation(String action) {
        if (action == null) return "Обращение без выбранного действия";
        return switch (action.strip()) {
            case "LOGIN" -> "Вход в систему";
            case "ACTION" -> "Действие пользователя";
            case "LOGOUT" -> "Выход из системы";
            default -> "Обращение с неизвестным действием";
        };
    }

    static String service(String path) {
        return path.equals("/auth") ? "служба подтверждения входа" : "служба выполнения действий";
    }

    static String httpMeaning(int status) {
        if (status >= 200 && status < 300) return "запрос принят без ошибки";
        return switch (status) {
            case 400 -> "система отклонила переданные данные";
            case 401, 403 -> "доступ запрещен";
            case 404 -> "нужный адрес не найден";
            case 405 -> "такой способ обращения не поддерживается";
            case 409 -> "запрос конфликтует с текущим состоянием";
            case 429 -> "превышено допустимое число обращений";
            case 500 -> "система сообщила о внутренней ошибке";
            case 502, 503 -> "внешняя служба недоступна или ответила с ошибкой";
            default -> status >= 300 && status < 400 ? "получен ответ перенаправления" : "получен другой ответ";
        };
    }

    static String difference(String expected, String actual) {
        return "Ожидалось: " + redact(expected) + ". Фактически: " + redact(actual) + ".";
    }

    static String businessReason(String reason) {
        if (reason.contains("^[0-9A-F]{32}$")) {
            return "приложение требует 32 символа и разрешает только цифры и буквы A-F";
        }
        String lower = reason.toLowerCase(Locale.ROOT);
        if (lower.contains("token") && lower.contains("not found")) return "пользователь не вошел в систему или уже вышел";
        if (lower.contains("token") && lower.contains("already exists")) return "пользователь уже вошел в систему";
        if (lower.contains("api key")) return "ключ доступа отсутствует или неверен";
        if (lower.equals("internal server error")) return "внутренняя ошибка приложения";
        if (lower.contains("invalid action")) return "действие отсутствует или не входит в список разрешенных";
        return redact(reason.replace("token:", "идентификатор пользователя:").replace("action:", "действие:"));
    }
}
