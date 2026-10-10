# Night Siege (Forge 1.20.1) — версия 2

Требования: JDK 17 и интернет при первой сборке.

## Сборка
- Linux/macOS: `./gradlew build`
- Windows: `gradlew.bat build`

При первом запуске gradlew сам скачает gradle-wrapper.jar, затем Gradle 8.1.1 и Forge (долго).
Готовый мод: `build/libs/nightsiege-1.0.0.jar` -> в папку `mods` (Forge 47.x, MC 1.20.1).

## Настройки
После первого запуска: `config/nightsiege-common.toml` (для одиночного мира: `saves/<мир>/serverconfig` не нужен, файл общий).
- `preset` = EASY / NORMAL / HARD / NIGHTMARE + отдельные множители (количество, здоровье, урон, скорость, ломание блоков, шанс особых зомби).
- `startDay`, `maxAlivePerPlayer`, `spawnIntervalTicks`, `requireDarkSpawn`, `maxSpawnLight` и др.

## Команды (нужны права оператора)
- `/siege start [волна]` — запустить волну сейчас (время станет ночью)
- `/siege stop` — остановить волну
- `/siege status` — состояние
- `/siege difficulty [easy|normal|hard|nightmare]` — показать/сменить сложность
