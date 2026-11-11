# Сборка RUarm

Android Studio / Gradle, JDK 17+, Android SDK 36:

```bash
./gradlew assembleRelease
```

Для подписи положи рядом с проектом `ruarm-release.jks` и `keystore.properties`
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`); без них релиз подписывается debug-ключом.
На Windows `aidl.exe` не любит длинные пути — собирай из короткого пути (например, через `subst`).

## Почему targetSdk 28

Встроенный Wine запускается из папки приложения. Начиная с targetSdk 29 Android запрещает запускать
исполняемые файлы из папки данных приложения, поэтому RUarm собран под Android 9 — так же,
как Winlator и GameNative. Из-за этого его нельзя опубликовать в Google Play.

## Готовые бинарники в репозитории

- `app/src/main/jniLibs/arm64-v8a/` — 7-Zip 26.06 и innoextract 1.13 с зависимостями из пакетов
  [Termux](https://github.com/termux/termux-packages): версии библиотек переименованы (`liblzma.so.5` → `libxlzma.so`
  и т. п.), RUNPATH заменён на `$ORIGIN`.
- `app/src/main/assets/wine.tar.xz` — Wine 11.16 (Hangover) + wowbox64 из
  [Termux x11-repo](https://github.com/termux/termux-packages/tree/master/x11-packages):
  - удалена отладочная информация и инструменты разработки;
  - пути `/data/data/com.termux/files` заменены на `/data/data/ru.ruarm/files`
    (поэтому идентификатор приложения должен оставаться `ru.ruarm`);
  - в 32-битном `kernel32.dll` скрыт экспорт `IsWow64Process2`, чтобы старые установщики Inno Setup
    видели процессор x64 и не отказывались работать на ARM64.

Лицензии сторонних компонентов — в [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
