"""Validate runtime evidence and render the selected UI matrix as a readable report."""
import argparse
import hashlib
import json
from pathlib import Path
from run_ui_matrix import ROOT, WORK, targets


def validate_result(target, result):
    if result["status"] != "passed":
        return result.get("error") or result["status"]
    name = result["target"]
    directory = WORK / "runs" / name
    actual = hashlib.sha256((directory / "mods/groups-gui.jar").read_bytes()).hexdigest()
    artifact = ROOT / "build/artifacts/plasmo-ui" / ("pv-addon-groups-gui-" + target["name"] + "-1.0.0.jar")
    if result.get("uiScenarios") != 10:
        return "Не выполнены все десять сценариев"
    if actual != result.get("artifactSha256") or actual != hashlib.sha256(artifact.read_bytes()).hexdigest():
        return "Хеш установленного JAR не соответствует проверенной сборке"
    loader = result["loaderVersion"]
    if target["loader"] == "fabric":
        installed = "fabric-loader-" + loader + "-" + target["game"]
    elif target["loader"] == "forge":
        installed = target["game"] + "-forge-" + loader
    else:
        installed = "neoforge-" + loader
    profile = json.loads((WORK / "minecraft/versions" / installed / (installed + ".json")).read_text())
    inherited = profile.get("inheritsFrom", profile.get("jar"))
    if inherited and inherited != target["game"]:
        return "Загрузчик использовал Minecraft " + inherited + " вместо " + target["game"]
    if not (directory / "screenshots/groups-gui-many-groups-pagination.png").exists():
        return "Нет скриншота последнего сценария"
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--allow-partial", action="store_true")
    parser.add_argument("--retry-report", action="append", type=Path, default=[])
    args = parser.parse_args()
    matrix = targets()
    data = json.loads((WORK / "report.json").read_text())
    results = {result["target"]: result for result in data["results"]}
    by_name = {target["loader"] + "-" + target["game"]: target for target in matrix}
    for retry_path in args.retry_report:
        for candidate in json.loads(retry_path.read_text())["results"]:
            name = candidate["target"]
            if name not in by_name:
                raise ValueError("Retry target outside selected matrix: " + name)
            previous = results.get(name)
            if candidate["status"] == "passed" and (not previous or previous["status"] != "passed"):
                error = validate_result(by_name[name], candidate)
                if error:
                    raise ValueError("Invalid retry evidence for " + name + ": " + error)
                if previous:
                    candidate["previousAttempts"] = previous.get("previousAttempts", []) + [{k: v for k, v in previous.items() if k != "previousAttempts"}]
                candidate["retryReport"] = str(retry_path.resolve())
                results[name] = candidate
    if args.retry_report:
        data["results"] = [results[name] for name in by_name if name in results]
        (WORK / "report.json").write_text(json.dumps(data, indent=2), encoding="utf-8")
    versions = json.loads((ROOT / "ui-test-matrix.json").read_text())["minecraft"]
    cells = {}
    errors = []
    passed = 0
    for target in matrix:
        name = target["loader"] + "-" + target["game"]
        result = results.get(name)
        label = "Ожидает"
        if result:
            error = validate_result(target, result)
            if error:
                errors.append(name + ": " + error)
                label = "Ошибка"
            else:
                passed += 1
                label = "10/10"
            log = (WORK / "runs" / name / "client.log").as_posix()
            label = f"[{label}]({log})"
        cells[target["game"], target["loader"]] = label
    lines = ["# Проверка интерфейса по версиям", "", f"Пройдено {passed} из {len(matrix)} сочетаний, {len(versions)} версий Minecraft.", "",
             "Клиенты запущены в отдельных папках. Проверены отрисовка и обработчики событий Plasmo на тестовых ответах протокола. Сетевая работа bridge на Paper не проверяется. Для G проверяется регистрация; нажатие через игровой ввод отдельно покрыто Fabric Client GameTest 26.2.", "",
             "| Minecraft | Fabric | Forge | NeoForge |", "| --- | --- | --- | --- |"]
    for version in versions:
        row = [version] + [cells.get((version, loader), "Не поддерживается") for loader in ("fabric", "forge", "neoforge")]
        lines.append("| " + " | ".join(row) + " |")
    lines += ["", "Сценарии: создание, пароль, права участника, обновление состава, ввод и изменение окна, настройки и прокрутка, приглашение и передача владения, модерация, подтверждение удаления и выхода, длинные списки и страницы.", ""]
    if errors:
        lines += ["## Ошибки", ""] + ["- " + error for error in errors] + [""]
    (WORK / "report.md").write_text("\n".join(lines), encoding="utf-8")
    print(f"Verified {passed}/{len(matrix)} runtime targets; report: {WORK / 'report.md'}")
    if errors or (passed != len(matrix) and not args.allow_partial):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
