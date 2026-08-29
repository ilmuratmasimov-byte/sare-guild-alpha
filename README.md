# Гильдия SARE — Android alpha

«Где задания становятся приключением.»

Простой Android alpha-прототип для тестирования среди друзей. Приложение написано на Java и показывает локальный HTML/JS-интерфейс через WebView. Тестовые и пользовательские данные хранятся только на устройстве в `localStorage`.

## Параметры

- package: `kz.sare.guild`
- minSdk: 26
- compileSdk / targetSdk: 35
- Java: 17
- Gradle: 8.9
- Android Gradle Plugin: 8.7.3
- начальная версия: `1` / `0.1.0`

## Сборка

GitHub Actions собирает подписанный release APK при push в `main`, ручном запуске и создании тега вида `v0.1.0`. При запуске по тегу workflow также публикует GitHub Release с файлами:

- `SARE-Guild.apk`
- `version.json`

Для подписи нужны repository secrets:

- `SARE_KEYSTORE_BASE64`
- `SARE_KEYSTORE_PASSWORD`
- `SARE_KEY_ALIAS`
- `SARE_KEY_PASSWORD`

Keystore и пароли нельзя добавлять в Git. Версию меняют только в `version.properties`; для каждого обновления `VERSION_CODE` должен увеличиваться.

## Обновления без Google Play

Кнопка **Профиль → Настройки → Проверить обновления** получает публичный файл:

`https://github.com/ilmuratmasimov-byte/sare-guild-alpha/releases/latest/download/version.json`

Если `versionCode` выше установленного, приложение предлагает скачать APK. После загрузки Android открывает стандартное системное подтверждение установки. Скрытая установка не используется.

Чтобы обновление установилось поверх текущей версии, package name и signing key должны оставаться неизменными, а `VERSION_CODE` — увеличиваться.

