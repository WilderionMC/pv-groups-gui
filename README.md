# PV Groups GUI

Клиентский GUI и серверный bridge для [pv-addon-groups](https://github.com/plasmoapp/pv-addon-groups).

У groups пока только команды. Этот аддон добавляет экран управления группами на клиенте и тонкий server/proxy bridge.

## Архитектура

```
[Fabric client]  --Plasmo Voice TCP packet 0x64-->  [proxy OR server bridge]
                                                          |
                                                          v
                                                 GroupsAddonService
                                                          |
                                                          v
                                                   pv-addon-groups
```

| Модуль | Роль |
|--------|------|
| `protocol` | Бинарный протокол GUI + хук в `PacketTcpCodec` |
| `bridge` | Общая логика + typed-адаптер к pv-addon-groups (`GroupsAddonService`) |
| `paper` | SERVER-scope addon (Paper/Folia/Fabric/Forge/NeoForge) |
| `velocity` | PROXY-scope addon (Velocity/BungeeCord) |
| `dist-server` | Один universal jar (как у `pv-addon-groups`) |
| `client` | Клиентский контроллер состояния |
| `fabric` / `fabric-*` | owo-ui экран + хоткей `G` (только Fabric, с **1.20.6**) |

### Клиентская матрица (Fabric + owo-lib)

Одна нода на линию Minecraft — последний патч:

| Модуль | Minecraft | Java | owo-lib |
|--------|-----------|------|---------|
| `fabric-1.20.6` | 1.20.6 | 21 | `0.12.9+1.20.5` |
| `fabric-1.21.11` | 1.21.11 | 21 | `0.13.0+1.21.11` |
| `fabric-26.1.2` | 26.1.2 | 25 | `0.13.1+26.1` |
| `fabric` | 26.2 | 25 | `0.13.1+26.2` |

**JVM:** server/bridge jar — bytecode **Java 8** (запуск с 8+ / 1.16.5+). Fabric-клиент — **Java 21** (1.20–1.21) или **Java 25** (26.x).

## Установка

### Сервер / прокси

Один jar: `dist-server/build/libs/pv-addon-groups-gui-1.0.0.jar` — ставится как плагин/мод на ту же платформу, где **Plasmo Voice** и **pv-addon-groups**:

| Сценарий | Куда класть jar |
|----------|-----------------|
| Velocity / BungeeCord сеть | на **прокси** (вместе с groups) |
| Paper / Folia / Fabric / Forge / NeoForge без прокси-groups | на **сервер** (вместе с groups) |

Entrypoints генерируются плагином `su.plo.voice.plugin.entrypoints` (`plugin.yml`, `velocity-plugin.json`, `bungee.yml`, `fabric.mod.json`, `mods.toml`, …).

### Клиент

1. Plasmo Voice **2.1.16+** (версия Minecraft должна совпадать с клиентским jar).
2. Нужный jar из `fabric*/build/libs/pv-addon-groups-gui-fabric-*.jar` (owo-lib внутри jar-in-jar).

## Использование

- Клавиша **G** (меняется в Controls → PV Groups GUI).
- Browse / create / join / leave / invite / delete.
- Пароль: поле «Пароль» перед Join / Create.

## Сборка

Universal server jar (все серверные/прокси платформы):

```bash
./gradlew :dist-server:shadowJar
```

Клиент (Fabric + owo, с 1.20.6):

```bash
./gradlew :fabric:jar :fabric-26.1.2:jar :fabric-1.21.11:jar :fabric-1.20.6:jar -PwithClient=true
```

Только актуальная линия (26.2):

```bash
./gradlew :fabric:jar -PwithClient=true
```

В **IntelliJ** модули `fabric*` подключаются при Gradle Sync автоматически. Если навигации нет — **Reload Gradle Project**. Можно явно задать `-PwithClient=true` в Settings → Build → Gradle → Gradle project properties.

Артефакты:
- `dist-server/build/libs/pv-addon-groups-gui-1.0.0.jar`
- `fabric/build/libs/pv-addon-groups-gui-fabric-26.2-*.jar`
- `fabric-26.1.2/build/libs/pv-addon-groups-gui-fabric-26.1.2-*.jar`
- `fabric-1.21.11/build/libs/pv-addon-groups-gui-fabric-1.21.11-*.jar`
- `fabric-1.20.6/build/libs/pv-addon-groups-gui-fabric-1.20.6-*.jar`

Нужны **JDK 25** для сборки (клиент Loom 26.x) и сеть для зависимостей. Server jar при этом компилируется в **Java 8** bytecode.

## Важно

- Bridge компилируется против **pv-addon-groups 1.1.1** (`compileOnly`, Modrinth). Права берутся из зарегистрированного `CommandHandler` (`registeredCommands["groups"]` → private `subCommands` → `checkCanExecute` / flag permissions), без дублирования строк `pv.addon.groups.*`.
- Кастомный TCP packet регистрируется в private registry Plasmo Voice (`PacketRegistryHook`). Packet id: `0x64`.
- Bridge на proxy **отменяет** forward пакета на backend, чтобы backend не пытался его разобрать.

## Структура ответа GUI

Клиент шлёт `GuiMessage.C2S.*`, сервер отвечает `Hello` / `Browse` / `ActionResult` / `State`.
