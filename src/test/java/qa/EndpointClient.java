package qa;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import java.net.URI;
import java.time.Duration;

import static io.restassured.RestAssured.given;
import static io.restassured.config.RestAssuredConfig.config;
import static io.restassured.config.HttpClientConfig.httpClientConfig;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.DecoderConfig.decoderConfig;
import static io.restassured.config.RedirectConfig.redirectConfig;

/** Обертка над Rest Assured: настройки HTTP собраны здесь, сценарии остаются в тестах. */
final class EndpointClient {
    private final RequestSpecification specification;

    EndpointClient(URI endpoint) {
        specification = new RequestSpecBuilder()
                .setBaseUri(endpoint.getScheme() + "://" + endpoint.getHost())
                .setPort(endpoint.getPort())
                .setBasePath(endpoint.getPath())
                .setAccept("application/json")
                .setConfig(config()
                        .httpClient(httpClientConfig()
                                .connectTimeout(Duration.ofSeconds(3))
                                .readTimeout(Duration.ofSeconds(10))
                                .connectionRequestTimeout(Duration.ofSeconds(3)))
                        .encoderConfig(encoderConfig().defaultContentCharset("UTF-8")
                                .defaultCharsetForContentType("UTF-8", ContentType.URLENC)
                                .appendDefaultContentCharsetToContentTypeIfUndefined(false))
                        .decoderConfig(decoderConfig().defaultContentCharset("UTF-8"))
                        .redirect(redirectConfig().followRedirects(false)))
                .build();
    }

    Response send(String token, String action, String key, String title) {
        var request = request(key, title).contentType(ContentType.URLENC);
        // null означает отсутствие поля, пустая строка отправляется как пустое значение.
        if (token != null) request.formParam("token", token);
        if (action != null) request.formParam("action", action);
        return request.when().post();
    }

    Response raw(String method, String body, String key, String contentType, String title) {
        // Для проверок неверного формата отправляем тело как есть, без сборки формы.
        return request(key, title).contentType(contentType).body(body).when().request(method);
    }

    private RequestSpecification request(String key, String title) {
        // Свежий запрос не должен унаследовать ключ или параметры предыдущего сценария.
        var request = given().spec(specification).filter(new MaskedAllureFilter(title));
        if (key != null) request.header("X-Api-Key", key);
        return request;
    }
}
