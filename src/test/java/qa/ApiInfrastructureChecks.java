package qa;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.restassured.builder.ResponseBuilder;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Проверяет саму тестовую инфраструктуру; запускается отдельно от сценариев приложения. */
class ApiInfrastructureChecks {
    private Response response(int status, String type, String body) {
        // Собираем ответ без сервера, чтобы проверить, какие нарушения замечает спецификация.
        return new ResponseBuilder().setStatusCode(status).setContentType(type).setBody(body).build();
    }

    static Stream<Arguments> invalidSuccess() {
        return Stream.of(
                Arguments.of(199, "application/json", "{\"result\":\"OK\"}"),
                Arguments.of(300, "application/json", "{\"result\":\"OK\"}"),
                Arguments.of(200, "text/plain", "{\"result\":\"OK\"}"),
                Arguments.of(200, "application/json", "{\"result\":\"ERROR\"}"),
                Arguments.of(200, "application/json", "{\"result\":\"OK\",\"extra\":true}"),
                Arguments.of(200, "application/json", "{}"),
                Arguments.of(200, "application/json", "[{\"result\":\"OK\"}]"));
    }

    @ParameterizedTest @MethodSource("invalidSuccess")
    void rejectsInvalidSuccessContract(int status, String type, String body) {
        assertThrows(AssertionError.class, () -> response(status, type, body)
                .then().spec(ResponseSpecifications.success()));
    }

    @ParameterizedTest @ValueSource(ints = {200, 201, 299})
    void acceptsSuccessfulStatusRange(int status) {
        response(status, "application/json; charset=UTF-8", "{\"result\":\"OK\"}")
                .then().spec(ResponseSpecifications.success());
    }

    @ParameterizedTest @ValueSource(strings = {
            "{}", "{\"result\":\"ERROR\"}", "{\"result\":\"ERROR\",\"message\":null}",
            "{\"result\":\"ERROR\",\"message\":42}", "{\"result\":\"ERROR\",\"message\":\"\"}",
            "{\"result\":\"ERROR\",\"message\":\" \\t\\n\"}",
            "{\"result\":\"ERROR\",\"message\":\"\\u2000\"}",
            "{\"result\":\"OK\",\"message\":\"reason\"}",
            "[{\"result\":\"ERROR\",\"message\":\"reason\"}]"})
    void rejectsInvalidErrorContract(String body) {
        assertThrows(AssertionError.class, () -> response(400, "application/json", body)
                .then().spec(ResponseSpecifications.error()));
    }

    @ParameterizedTest @ValueSource(ints = {200, 400, 500})
    void errorEnvelopeDoesNotInventAStatusOrForbidAdditionalFields(int status) {
        response(status, "application/json", "{\"result\":\"ERROR\",\"message\":\"Отказ\",\"extra\":true}")
                .then().spec(ResponseSpecifications.error());
    }

    @Test
    void encodesFormValuesAndDistinguishesMissingFromEmptyFields() {
        var server = new WireMockServer(wireMockConfig().dynamicPort().bindAddress("127.0.0.1"));
        server.start();
        try {
            server.stubFor(post(urlEqualTo("/endpoint")).willReturn(okJson("{\"result\":\"OK\"}")));
            var client = new EndpointClient(URI.create(server.baseUrl() + "/endpoint"));
            String value = "А +&=%";
            // Кириллица и символы формы должны дойти до сервера без потери или двойного кодирования.
            client.send(value, " LOGIN ", "fixture-key", "Проверка кодировки формы")
                    .then().spec(ResponseSpecifications.success());
            server.verify(1, postRequestedFor(urlEqualTo("/endpoint"))
                    .withFormParam("token", equalTo(value)).withFormParam("action", equalTo(" LOGIN "))
                    .withHeader("X-Api-Key", equalTo("fixture-key"))
                    .withHeader("Accept", equalTo("application/json")));
            server.resetRequests();
            client.send(null, null, null, "Проверка отсутствующих полей").then().statusCode(200);
            server.verify(1, postRequestedFor(urlEqualTo("/endpoint"))
                    .withFormParam("token", absent()).withFormParam("action", absent())
                    .withHeader("X-Api-Key", absent()));
            server.resetRequests();
            client.send("", "", "", "Проверка пустых полей").then().statusCode(200);
            server.verify(1, postRequestedFor(urlEqualTo("/endpoint"))
                    .withFormParam("token", equalTo("")).withFormParam("action", equalTo(""))
                    .withHeader("X-Api-Key", equalTo("")));
        } finally {
            server.stop();
        }
    }
}
