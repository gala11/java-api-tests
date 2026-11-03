package qa;

import io.qameta.allure.model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Проверки оформления отчета. Запускаются отдельно с -Dtest=ReportSupportChecks. */
class ReportSupportChecks {
    @Test
    void removesSensitiveFieldsEvenWithoutARegisteredValue() {
        String raw = "X-Api-Key: header-fixture\nAuthorization: Bearer auth-fixture\nCookie: session=fixture\n"
                + "token=A&action=LOGIN\n{\"password\":\"secret-fixture\",\"email\":\"qa@example.test\",\"result\":\"ERROR\"}";
        String safe = ReportSupport.redact(raw);
        for (String secret : List.of("header-fixture", "auth-fixture", "session=fixture", "token=A", "secret-fixture", "qa@example.test")) {
            assertFalse(safe.contains(secret), "Confidential value leaked: " + secret);
        }
        assertTrue(safe.contains("action=LOGIN"));
        assertTrue(safe.contains("\"result\":\"ERROR\""));
    }

    @Test
    void masksEncodedAndFreeTextValuesWithoutChangingTechnicalCodes() {
        ReportSupport.rememberSecret("fixture + credential");
        String safe = ReportSupport.redact("token=fixture+%2B+credential\nexpected fixture + credential but got HTTP 500");
        assertFalse(safe.contains("credential"));
        assertTrue(safe.contains("HTTP 500"));
    }

    @Test
    void preservesFailureAndStackFramesWhileAddingReadableExplanation() {
        // Понятное объяснение должно сохранить статус падения и место исходной ошибки.
        String secret = "FIXTURE01234567890123456789012345";
        ReportSupport.rememberSecret(secret);
        StepResult step = new StepResult().setName("Подтвердить вход").setStatus(Status.FAILED)
                .setParameters(List.of(new Parameter().setName("Ожидаемый результат").setValue("Вход разрешен"),
                        new Parameter().setName("Фактический результат").setValue("Получен отказ")));
        TestResult result = new TestResult().setName("Вход").setStatus(Status.FAILED).setSteps(List.of(step))
                .setStatusDetails(new StatusDetails().setMessage("Original assertion for " + secret)
                        .setTrace("OriginalException: " + secret + "\n at qa.Fixture.check(Fixture.java:42)"));
        new ReadableAllureListener().beforeTestWrite(result);
        assertEquals(Status.FAILED, result.getStatus());
        assertTrue(result.getDescription().contains("Ожидалось: Вход разрешен"));
        assertTrue(result.getDescription().contains("Фактически: Получен отказ"));
        assertTrue(result.getStatusDetails().getMessage().contains("Original assertion"));
        assertTrue(result.getStatusDetails().getTrace().contains("qa.Fixture.check(Fixture.java:42)"));
        assertFalse(result.getStatusDetails().getTrace().contains(secret));
    }

    @Test
    void doesNotTurnAnExecutionErrorIntoAProductFailureOrSuccess() {
        TestResult result = new TestResult().setName("Запуск").setStatus(Status.BROKEN)
                .setStatusDetails(new StatusDetails().setMessage("Connection failed").setTrace("IOException"));
        new ReadableAllureListener().beforeTestWrite(result);
        assertEquals(Status.BROKEN, result.getStatus());
        assertTrue(result.getDescription().contains("не удалось завершить"));
        assertEquals("IOException", result.getStatusDetails().getTrace());
    }
}
