# Автотесты API

Тесты Spring Boot приложения с единственным эндпоинтом `POST /endpoint`. Проверяются вход (`LOGIN`), выполнение действия (`ACTION`), выход (`LOGOUT`), API-ключ, валидация данных и обработка ошибок внешнего сервиса.

Стек: **Java 17, Maven, JUnit 5, Rest Assured 6.3.0, WireMock, Allure**.

## Запуск

Нужны JDK 17 и Maven. Переменная `JAVA_HOME` должна указывать на каталог JDK, команда `mvn` должна быть доступна в терминале. При первом запуске требуется интернет для загрузки зависимостей.

Поместите `internal-0.0.1-SNAPSHOT.jar` в корень проекта и выполните в PowerShell:

```powershell
mvn clean test "-Dmaven.repo.local=.tools/m2"
```

Для JAR в другом каталоге:

```powershell
mvn clean test "-Dmaven.repo.local=.tools/m2" "-Dsut.jar=D:\apps\internal-0.0.1-SNAPSHOT.jar"
```

Тесты автоматически запускают настоящее приложение и WireMock на свободных локальных портах, затем завершают их работу. API-ключ создается случайно для запуска. Docker и ручной запуск сервисов не нужны.

## Структура

Применен подход **API Client Wrapper**:

- `EndpointClient` оборачивает Rest Assured: запросы, формы, заголовки и таймауты.
- `ResponseSpecifications` проверяет HTTP-ответы и JSON через Rest Assured и Hamcrest.
- `ServiceTest` управляет окружением и общими шагами сценариев.
- `*Test` содержат проверки доступа, сессий, валидации, протокола и внешних вызовов.
- `MaskedAllureFilter` добавляет запросы и ответы в Allure с маскированием значений доступа.

Успешный JSON должен содержать только `result: "OK"`. Для отказа проверяются `result: "ERROR"` и непустая текстовая причина `message`. Отдельно проверяются HTTP-статусы, Content-Type и обращения к внешнему сервису.

## Отчеты и результаты

После выполнения тестов сформируйте отчет, в том числе если Maven сообщил о падениях:

```powershell
mvn allure:report "-Dmaven.repo.local=.tools/m2"
mvn allure:serve "-Dmaven.repo.local=.tools/m2"
```

Первая команда сохраняет отчет в `target/site/allure-maven-plugin`, вторая открывает его через локальный сервер. В отчете доступны русские названия сценариев, шаги и диагностические вложения с замаскированными значениями доступа.

В наборе остаются активные проверки известных проблем приложения, поэтому прогон может завершиться с кодом 1. JAR приложения и сгенерированные отчеты в Git не включаются.

11 сценариев `CharacterizationTest` фиксируют поведение, не определенное заданием. Работа настоящего внешнего сервиса и нагрузка этим набором не проверяются.

Дополнительно доступны 27 проверок инфраструктуры, не входящих в 82 сценария приложения:

```powershell
mvn test "-Dmaven.repo.local=.tools/m2" "-Dtest=ApiInfrastructureChecks,ReportSupportChecks" "-Dallure.results.directory=target/infrastructure-results"
```
