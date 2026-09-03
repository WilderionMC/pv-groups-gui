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
| `fabric` | owo-ui экран + хоткей `G` (Minecraft **26.2**, Java **25**) |

## Установка

### Сервер / прокси

Один jar: `dist-server/build/libs/pv-addon-groups-gui-1.0.0.jar` — ставится как плагин/мод на ту же платформу, где **Plasmo Voice** и **pv-addon-groups**:

| Сценарий | Куда класть jar |
|----------|-----------------|
| Velocity / BungeeCord сеть | на **прокси** (вместе с groups) |
| Paper / Folia / Fabric / Forge / NeoForge без прокси-groups | на **сервер** (вместе с groups) |

Entrypoints генерируются плагином `su.plo.voice.plugin.entrypoints` (`plugin.yml`, `velocity-plugin.json`, `bungee.yml`, `fabric.mod.json`, `mods.toml`, …).

### Клиент

1. Plasmo Voice **2.1.16+** (Fabric **26.2**).
2. `fabric/build/libs/pv-addon-groups-gui-fabric-*.jar` (owo-lib внутри jar-in-jar).

## Использование

- Клавиша **G** (меняется в Controls → PV Groups GUI).
- Browse / create / join / leave / invite / delete.
- Пароль: поле «Пароль» перед Join / Create.

## Сборка

Universal server jar (все серверные/прокси платформы):

```bash
./gradlew :dist-server:shadowJar
```

Клиент (Fabric, Minecraft 26.2, Java 25):

```bash
./gradlew :fabric:jar -PwithClient=true
```

В **IntelliJ** модуль `fabric` подключается при Gradle Sync автоматически. Если навигации нет — **Reload Gradle Project**. Можно явно задать `-PwithClient=true` в Settings → Build → Gradle → Gradle project properties.

Артефакты:
- `dist-server/build/libs/pv-addon-groups-gui-1.0.0.jar`
- `fabric/build/libs/pv-addon-groups-gui-fabric-*.jar`

Нужны **JDK 25** и сеть для зависимостей.

## Важно

- Bridge компилируется против **pv-addon-groups 1.1.1** (`compileOnly`, Modrinth). Права берутся из зарегистрированного `CommandHandler` (`registeredCommands["groups"]` → private `subCommands` → `checkCanExecute` / flag permissions), без дублирования строк `pv.addon.groups.*`.
- Кастомный TCP packet регистрируется в private registry Plasmo Voice (`PacketRegistryHook`). Packet id: `0x64`.
- Bridge на proxy **отменяет** forward пакета на backend, чтобы backend не пытался его разобрать.

## Структура ответа GUI

Клиент шлёт `GuiMessage.C2S.*`, сервер отвечает `Hello` / `Browse` / `ActionResult` / `State`.
