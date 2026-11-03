package qa;

import io.restassured.builder.ResponseSpecBuilder;
import io.restassured.parsing.Parser;
import io.restassured.specification.ResponseSpecification;
import org.hamcrest.Description;
import org.hamcrest.TypeSafeMatcher;

import java.util.Map;

import static org.hamcrest.Matchers.*;

/** Общие правила проверки ответов. Для каждого вызова создается отдельная спецификация. */
final class ResponseSpecifications {
    private ResponseSpecifications() { }

    static ResponseSpecification success() {
        // Успешный ответ содержит только result. Лишние поля тоже считаем нарушением контракта.
        return jsonObject()
                .expectStatusCode(describedAs("Успешная операция должна возвращать HTTP 2xx",
                        allOf(greaterThanOrEqualTo(200), lessThan(300))))
                .expectBody("", hasEntry("result", "OK"))
                .expectBody("", describedAs("Успешный ответ должен содержать только result", aMapWithSize(1)))
                .build();
    }

    static ResponseSpecification error() {
        // Конкретный текст причины не задан. Принимаем любую строку с непробельными символами.
        // Код HTTP проверяется отдельно там, где для него есть ожидание в сценарии.
        return jsonObject()
                .expectBody("", hasEntry("result", "ERROR"))
                .expectBody("", hasEntry(equalTo("message"), new TypeSafeMatcher<String>() {
                    @Override protected boolean matchesSafely(String value) { return !value.isBlank(); }
                    @Override public void describeTo(Description description) {
                        description.appendText("непустая текстовая причина отказа");
                    }
                })).build();
    }

    private static ResponseSpecBuilder jsonObject() {
        return new ResponseSpecBuilder()
                .setDefaultParser(Parser.JSON)
                .expectHeader("Content-Type", describedAs("Ответ в формате application/json",
                        containsStringIgnoringCase("application/json")))
                .expectBody("", describedAs("JSON-объект с результатом операции", instanceOf(Map.class)));
    }
}
