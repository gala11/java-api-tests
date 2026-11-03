package qa;

import io.qameta.allure.listener.TestLifecycleListener;
import io.qameta.allure.model.*;

import java.util.List;

/** Дополняет результат понятным пояснением, сохраняя статус проверки и исходный стек ошибки. */
public final class ReadableAllureListener implements TestLifecycleListener {
    @Override
    public void beforeTestWrite(TestResult result) {
        result.setName(ReportSupport.redact(result.getName()));
        sanitizeParameters(result.getParameters());
        result.getSteps().forEach(this::sanitizeStep);
        sanitizeDetails(result.getStatusDetails());

        String outcome;
        if (result.getStatus() == Status.PASSED) {
            outcome = "Проверка пройдена. Все выполненные проверки соответствуют ожидаемому поведению.";
        } else if (result.getStatus() == Status.SKIPPED) {
            outcome = "Проверка пропущена. Поведение системы этим сценарием не подтверждено.";
        } else {
            StepResult failed = firstFailure(result.getSteps());
            String action = failed == null ? "выполнение сценария" : failed.getName();
            String expected = failed == null ? "" : parameter(failed, "Ожидаемый результат");
            String actual = failed == null ? "" : parameter(failed, "Фактический результат");
            outcome = (result.getStatus() == Status.FAILED ? "Проверка не пройдена." : "Проверку не удалось завершить.")
                    + " Проблема на действии: " + action + "."
                    + (expected.isBlank() ? "" : "\n\nОжидалось: " + expected + ".")
                    + (actual.isBlank() ? "" : "\n\nФактически: " + actual + ".");
            StatusDetails details = result.getStatusDetails();
            if (details != null) {
                // После пояснения оставляем техническую причину: она нужна для разбора падения.
                details.setMessage(outcome + "\n\nТехническая причина:\n" + details.getMessage());
            }
        }
        String purpose = result.getDescription() == null ? "" : result.getDescription();
        result.setDescription(ReportSupport.redact(purpose + "\n\n### Итог проверки\n\n" + outcome));
    }

    private StepResult firstFailure(List<StepResult> steps) {
        for (StepResult step : steps) {
            if (step.getStatus() == Status.FAILED || step.getStatus() == Status.BROKEN) {
                if (!parameter(step, "Ожидаемый результат").isEmpty()) return step;
                StepResult child = firstFailure(step.getSteps());
                return child == null ? step : child;
            }
        }
        return null;
    }

    private String parameter(StepResult step, String name) {
        return step.getParameters().stream().filter(p -> name.equals(p.getName()))
                .map(Parameter::getValue).findFirst().orElse("");
    }

    private void sanitizeStep(StepResult step) {
        step.setName(ReportSupport.redact(step.getName()));
        sanitizeParameters(step.getParameters());
        sanitizeDetails(step.getStatusDetails());
        step.getSteps().forEach(this::sanitizeStep);
    }

    private void sanitizeParameters(List<Parameter> parameters) {
        parameters.forEach(p -> p.setValue(ReportSupport.redact(p.getValue())));
    }

    private void sanitizeDetails(StatusDetails details) {
        if (details != null) {
            details.setMessage(ReportSupport.redact(details.getMessage()));
            details.setTrace(ReportSupport.redact(details.getTrace()));
        }
    }
}
