package qa;

import io.restassured.filter.Filter;
import io.restassured.filter.FilterContext;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;

/** Сохраняет обмен с приложением в Allure. Значения доступа скрывает ReportSupport. */
final class MaskedAllureFilter implements Filter {
    private final String title;

    MaskedAllureFilter(String title) {
        this.title = title;
    }

    @Override
    public Response filter(FilterableRequestSpecification request,
                           FilterableResponseSpecification expectations, FilterContext context) {
        StringBuilder diagnostic = new StringBuilder(request.getMethod()).append(" ").append(request.getURI())
                .append("\nЗаголовки и параметры спецификации запроса:\n").append(request.getHeaders()).append("\n");
        // Токен скрываем до сборки вложения, в том числе короткие значения из негативных тестов.
        request.getFormParams().forEach((name, value) -> diagnostic.append(name).append("=")
                .append(name.equalsIgnoreCase("token") ? "[скрыто]" : value).append("\n"));
        Object body = request.getBody();
        if (body != null) diagnostic.append(body).append("\n");
        Response response;
        try {
            response = context.next(request, expectations);
        } catch (RuntimeException failure) {
            ReportSupport.attachment("Диагностика: обращение без полученного ответа", diagnostic.toString());
            throw failure;
        }
        diagnostic.append("\nОтвет системы: HTTP ").append(response.statusCode())
                .append("\n").append(response.headers()).append("\n").append(response.asString());
        ReportSupport.attachment("Диагностика: запрос и ответ - " + title, diagnostic.toString());
        return response;
    }
}
