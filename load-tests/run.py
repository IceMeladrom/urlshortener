#!/usr/bin/env python3

from __future__ import annotations

import argparse
import concurrent.futures
import json
import os
import random
import shutil
import subprocess
import sys
import threading
import time
import uuid
from datetime import datetime
from pathlib import Path
from typing import List, Optional, Set, Tuple
from urllib import error, request
from urllib.parse import urlparse, urlunparse


SCRIPT_DIR = Path(__file__).resolve().parent
SCENARIOS = ("nominal", "capacity", "stress", "soak", "failover")
PREPARE_MODES = ("auto", "docker", "api")


def env_bool(name: str, default: bool) -> bool:
    raw = os.environ.get(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "y", "on"}


def env_int(name: str, default: int) -> int:
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    return int(raw)


def env_float(name: str, default: float) -> float:
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    return float(raw)


def bool_text(value: bool) -> str:
    return "true" if value else "false"


def resolve_path(value: str) -> Path:
    path = Path(value)
    if path.is_absolute():
        return path
    return SCRIPT_DIR / path


def normalize_api_base(raw_url: str) -> str:
    url = raw_url.strip()
    if not url:
        raise ValueError("BASE_URL не может быть пустым")
    if "://" not in url:
        url = "http://" + url

    parsed = urlparse(url)
    path = parsed.path.rstrip("/")
    if not path.endswith("/api/v1"):
        path = f"{path}/api/v1" if path else "/api/v1"

    normalized = parsed._replace(path=path, params="", query="", fragment="")
    return urlunparse(normalized).rstrip("/")


def check_binary(binary: str, hint: str) -> None:
    if shutil.which(binary) is None:
        raise RuntimeError(hint)


def docker_container_running(container_name: str) -> bool:
    if shutil.which("docker") is None:
        return False

    result = subprocess.run(
        ["docker", "ps", "--format", "{{.Names}}"],
        cwd=SCRIPT_DIR,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    if result.returncode != 0:
        return False

    return container_name in {line.strip() for line in result.stdout.splitlines()}


def require_docker_container(container_name: str, description: str) -> None:
    if shutil.which("docker") is None:
        raise RuntimeError("docker не найден в PATH")
    if not docker_container_running(container_name):
        raise RuntimeError(f"Контейнер {description} '{container_name}' не запущен")


def api_base_is_local(api_base: str) -> bool:
    hostname = (urlparse(api_base).hostname or "").lower()
    return hostname in {"localhost", "127.0.0.1", "::1"}


def select_prepare_mode(requested_mode: str, api_base: str, db_container: str, redis_container: str) -> str:
    if requested_mode != "auto":
        return requested_mode

    if api_base_is_local(api_base) and docker_container_running(db_container) and docker_container_running(redis_container):
        return "docker"
    return "api"


def select_scenario(scenario: str) -> str:
    if scenario:
        if scenario not in SCENARIOS:
            raise RuntimeError(f"Неизвестный сценарий: {scenario}")
        return scenario

    print("Выберите сценарий:")
    print("1 - номинальная нагрузка")
    print("2 - поиск предельной пропускной способности")
    print("3 - резкий скачок нагрузки")
    print("4 - длительная нагрузка")
    print("5 - отказ Redis")
    choice = input("Номер сценария: ").strip()
    mapping = {
        "1": "nominal",
        "2": "capacity",
        "3": "stress",
        "4": "soak",
        "5": "failover",
    }
    if choice not in mapping:
        raise RuntimeError("Неизвестный сценарий")
    return mapping[choice]


def write_popular_codes(codes_file: Path, popular_codes_file: Path, popular_percent: int) -> None:
    codes = [line.strip() for line in codes_file.read_text(encoding="utf-8").splitlines() if line.strip()]
    if not codes:
        raise RuntimeError(f"Файл {codes_file} пуст")

    popular_rows = max(1, len(codes) * popular_percent // 100)
    popular_rows = min(popular_rows, len(codes))
    popular = random.sample(codes, popular_rows)
    popular_codes_file.write_text("\n".join(popular) + "\n", encoding="utf-8")
    print(f"Выбраны популярные ссылки: {popular_rows}")


def run_k6(
    *,
    k6_bin: str,
    api_base: str,
    scenario: str,
    summary_file: str,
    warmup_duration: str,
    warmup_rate: int,
    codes_file: str,
    popular_codes_file: str,
    dashboard: bool,
    html_report: Optional[str] = None,
) -> None:
    env = os.environ.copy()
    env.update(
        {
            "BASE_URL": api_base,
            "SCENARIO": scenario,
            "WARMUP_DURATION": warmup_duration,
            "WARMUP_RATE": str(warmup_rate),
            "CODES_FILE": codes_file,
            "POPULAR_CODES_FILE": popular_codes_file,
            "K6_WEB_DASHBOARD": bool_text(dashboard),
        }
    )
    if html_report:
        env["K6_WEB_DASHBOARD_EXPORT"] = html_report
    else:
        env.pop("K6_WEB_DASHBOARD_EXPORT", None)

    subprocess.run(
        [k6_bin, "run", "--summary-export", summary_file, "methodology_test.js"],
        cwd=SCRIPT_DIR,
        env=env,
        check=True,
    )


def prepare_with_docker(args: argparse.Namespace, codes_path: Path, popular_codes_path: Path) -> None:
    require_docker_container(args.db_container, "PostgreSQL")
    require_docker_container(args.redis_container, "Redis")

    print("Очищаю Redis")
    subprocess.run(
        ["docker", "exec", args.redis_container, "redis-cli", "FLUSHALL"],
        cwd=SCRIPT_DIR,
        stdout=subprocess.DEVNULL,
        check=True,
    )

    print(f"Создаю {args.num_rows} тестовых ссылок через PostgreSQL")
    temp_codes = codes_path.with_name(codes_path.name + ".tmp")
    with (SCRIPT_DIR / "seed.sql").open("rb") as stdin, temp_codes.open("wb") as stdout:
        subprocess.run(
            [
                "docker",
                "exec",
                "-i",
                args.db_container,
                "psql",
                "-U",
                "postgres",
                "-d",
                "shortener",
                "-v",
                f"num_rows={args.num_rows}",
                "-q",
                "-A",
                "-f",
                "-",
            ],
            cwd=SCRIPT_DIR,
            stdin=stdin,
            stdout=stdout,
            check=True,
        )
    temp_codes.replace(codes_path)

    if not codes_path.exists() or codes_path.stat().st_size == 0:
        raise RuntimeError(f"Файл {codes_path} пуст")

    write_popular_codes(codes_path, popular_codes_path, args.popular_percent)


def post_shorten(api_base: str, original_url: str, timeout: float) -> str:
    payload = json.dumps({"url": original_url}).encode("utf-8")
    http_request = request.Request(
        f"{api_base}/shorten",
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )

    try:
        with request.urlopen(http_request, timeout=timeout) as response:
            response_body = response.read().decode("utf-8")
            if response.status not in (200, 201):
                raise RuntimeError(f"HTTP {response.status}: {response_body[:300]}")
    except error.HTTPError as exc:
        response_body = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {exc.code}: {response_body[:300]}") from exc
    except error.URLError as exc:
        raise RuntimeError(str(exc.reason)) from exc

    try:
        data = json.loads(response_body)
    except json.JSONDecodeError as exc:
        raise RuntimeError(f"Некорректный JSON в ответе: {response_body[:300]}") from exc

    short_code = data.get("shortCode")
    if not short_code:
        raise RuntimeError(f"В ответе нет shortCode: {response_body[:300]}")
    return str(short_code)


def prepare_one_via_api(api_base: str, run_id: str, index: int, timeout: float) -> Tuple[int, str]:
    original_url = f"example.test/load/{run_id}/{index}"
    return index, post_shorten(api_base, original_url, timeout)


def prepare_with_api(args: argparse.Namespace, codes_path: Path, popular_codes_path: Path) -> None:
    print(f"Создаю {args.num_rows} тестовых ссылок через HTTP API")
    print(f"Параллельность подготовки: {args.api_prepare_concurrency}")
    print("Удаленная подготовка не очищает PostgreSQL и Redis напрямую")

    temp_codes = codes_path.with_name(codes_path.name + ".tmp")
    completed = 0
    errors: List[str] = []
    run_id = datetime.now().strftime("%Y%m%d_%H%M%S") + "_" + uuid.uuid4().hex[:8]
    next_index = 1

    def submit_next(executor: concurrent.futures.Executor, pending: Set[concurrent.futures.Future]) -> int:
        nonlocal next_index
        if next_index > args.num_rows:
            return next_index
        current = next_index
        next_index += 1
        pending.add(
            executor.submit(
                prepare_one_via_api,
                args.api_base,
                run_id,
                current,
                args.api_prepare_timeout,
            )
        )
        return next_index

    try:
        with temp_codes.open("w", encoding="utf-8") as output:
            with concurrent.futures.ThreadPoolExecutor(max_workers=args.api_prepare_concurrency) as executor:
                pending: Set[concurrent.futures.Future] = set()
                for _ in range(min(args.api_prepare_concurrency, args.num_rows)):
                    submit_next(executor, pending)

                while pending:
                    done, pending = concurrent.futures.wait(
                        pending,
                        return_when=concurrent.futures.FIRST_COMPLETED,
                    )
                    for future in done:
                        try:
                            _, short_code = future.result()
                        except Exception as exc:  # noqa: BLE001 - выводим исходную ошибку HTTP/сети
                            errors.append(str(exc))
                            if len(errors) >= args.api_prepare_max_errors:
                                for pending_future in pending:
                                    pending_future.cancel()
                                raise RuntimeError(
                                    "Подготовка через API остановлена после ошибок: "
                                    + "; ".join(errors[:3])
                                ) from exc
                            continue

                        completed += 1
                        output.write(short_code + "\n")
                        if completed == args.num_rows or completed % max(1, args.num_rows // 10) == 0:
                            print(f"Создано ссылок: {completed}/{args.num_rows}")
                        submit_next(executor, pending)

            if errors or completed != args.num_rows:
                raise RuntimeError(
                    f"Создано {completed}/{args.num_rows} ссылок. "
                    f"Ошибок: {len(errors)}. Первая ошибка: {errors[0] if errors else 'нет'}"
                )
    except Exception:
        temp_codes.unlink(missing_ok=True)
        raise

    temp_codes.replace(codes_path)
    write_popular_codes(codes_path, popular_codes_path, args.popular_percent)


def run_prepare(args: argparse.Namespace, codes_path: Path, popular_codes_path: Path) -> None:
    if not args.prepare_data:
        if not codes_path.exists() or codes_path.stat().st_size == 0:
            raise RuntimeError(
                f"PREPARE_DATA=false, но файл {codes_path} отсутствует или пуст. "
                "Скопируйте актуальный shortcodes.txt или включите подготовку данных."
            )
        return

    prepare_mode = select_prepare_mode(args.prepare_mode, args.api_base, args.db_container, args.redis_container)
    print(f"Режим подготовки данных: {prepare_mode}")
    if prepare_mode == "docker":
        prepare_with_docker(args, codes_path, popular_codes_path)
    elif prepare_mode == "api":
        prepare_with_api(args, codes_path, popular_codes_path)
    else:
        raise RuntimeError(f"Неизвестный режим подготовки данных: {prepare_mode}")

    if args.run_warmup:
        print("Прогреваю популярные ссылки")
        run_k6(
            k6_bin=args.k6_bin,
            api_base=args.api_base,
            scenario="warmup",
            summary_file="summary_warmup.json",
            warmup_duration=args.warmup_duration,
            warmup_rate=args.warmup_rate,
            codes_file=args.codes_file,
            popular_codes_file=args.popular_codes_file,
            dashboard=False,
        )

        if prepare_mode == "docker":
            subprocess.run(
                [
                    "docker",
                    "exec",
                    args.redis_container,
                    "redis-cli",
                    "DEL",
                    "links:clicks:buffer",
                    "links:expiry:buffer",
                ],
                cwd=SCRIPT_DIR,
                stdout=subprocess.DEVNULL,
                check=True,
            )
        else:
            print("Буферы Redis после прогрева не очищены: удаленный HTTP-режим не имеет доступа к Redis")


def should_control_failover(value: str, redis_container: str) -> bool:
    normalized = value.strip().lower()
    if normalized == "auto":
        return docker_container_running(redis_container)
    if normalized in {"1", "true", "yes", "y", "on"}:
        require_docker_container(redis_container, "Redis")
        return True
    if normalized in {"0", "false", "no", "n", "off"}:
        return False
    raise RuntimeError("FAILOVER_CONTROL должен быть auto, true или false")


def start_failover_worker(args: argparse.Namespace) -> threading.Thread:
    def worker() -> None:
        time.sleep(args.failover_stop_after)
        print("Останавливаю Redis")
        subprocess.run(["docker", "stop", args.redis_container], cwd=SCRIPT_DIR, stdout=subprocess.DEVNULL, check=False)
        time.sleep(args.failover_down_seconds)
        print("Запускаю Redis")
        subprocess.run(["docker", "start", args.redis_container], cwd=SCRIPT_DIR, stdout=subprocess.DEVNULL, check=False)

    thread = threading.Thread(target=worker, daemon=True)
    thread.start()
    return thread


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Подготовка данных и запуск k6-сценариев URL Shortener")
    parser.add_argument("--base-url", default=os.environ.get("BASE_URL", "http://localhost:8080/api/v1"))
    parser.add_argument("--scenario", default=os.environ.get("SCENARIO", ""))
    parser.add_argument("--prepare-mode", choices=PREPARE_MODES, default=os.environ.get("PREPARE_MODE", "auto"))

    parser.set_defaults(prepare_data=env_bool("PREPARE_DATA", True))
    parser.add_argument("--prepare-data", dest="prepare_data", action="store_true")
    parser.add_argument("--no-prepare", dest="prepare_data", action="store_false")

    parser.set_defaults(run_warmup=env_bool("RUN_WARMUP", True))
    parser.add_argument("--run-warmup", dest="run_warmup", action="store_true")
    parser.add_argument("--skip-warmup", dest="run_warmup", action="store_false")

    parser.add_argument("--num-rows", type=int, default=env_int("NUM_ROWS", 100000))
    parser.add_argument("--popular-percent", type=int, default=env_int("POPULAR_PERCENT", 20))
    parser.add_argument("--codes-file", default=os.environ.get("CODES_FILE", "shortcodes.txt"))
    parser.add_argument("--popular-codes-file", default=os.environ.get("POPULAR_CODES_FILE", "shortcodes_popular.txt"))
    parser.add_argument("--warmup-duration", default=os.environ.get("WARMUP_DURATION", "1m"))
    parser.add_argument("--warmup-rate", type=int, default=env_int("WARMUP_RATE", 500))
    parser.add_argument("--k6-bin", default=os.environ.get("K6_BIN", "k6"))

    parser.add_argument("--db-container", default=os.environ.get("DB_CONTAINER", "shortener-db"))
    parser.add_argument("--redis-container", default=os.environ.get("REDIS_CONTAINER", "shortener-redis"))
    parser.add_argument("--failover-control", default=os.environ.get("FAILOVER_CONTROL", "auto"))
    parser.add_argument("--failover-stop-after", type=int, default=env_int("FAILOVER_STOP_AFTER", 120))
    parser.add_argument("--failover-down-seconds", type=int, default=env_int("FAILOVER_DOWN_SECONDS", 180))

    parser.add_argument("--api-prepare-concurrency", type=int, default=env_int("API_PREPARE_CONCURRENCY", 32))
    parser.add_argument("--api-prepare-timeout", type=float, default=env_float("API_PREPARE_TIMEOUT", 10.0))
    parser.add_argument("--api-prepare-max-errors", type=int, default=env_int("API_PREPARE_MAX_ERRORS", 10))
    return parser


def validate_args(args: argparse.Namespace) -> None:
    if args.num_rows < 1:
        raise RuntimeError("NUM_ROWS должен быть больше 0")
    if args.popular_percent < 0:
        raise RuntimeError("POPULAR_PERCENT не может быть отрицательным")
    if args.api_prepare_concurrency < 1:
        raise RuntimeError("API_PREPARE_CONCURRENCY должен быть больше 0")
    if args.api_prepare_timeout <= 0:
        raise RuntimeError("API_PREPARE_TIMEOUT должен быть больше 0")
    if args.api_prepare_max_errors < 1:
        raise RuntimeError("API_PREPARE_MAX_ERRORS должен быть больше 0")


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    validate_args(args)

    args.api_base = normalize_api_base(args.base_url)
    args.scenario = select_scenario(args.scenario)

    check_binary(args.k6_bin, "k6 не найден в PATH")

    codes_path = resolve_path(args.codes_file)
    popular_codes_path = resolve_path(args.popular_codes_file)

    run_prepare(args, codes_path, popular_codes_path)

    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    html_report = f"report_{args.scenario}_{stamp}.html"
    json_report = f"summary_{args.scenario}_{stamp}.json"
    failover_controlled = False

    if args.scenario == "failover":
        failover_controlled = should_control_failover(args.failover_control, args.redis_container)
        if failover_controlled:
            start_failover_worker(args)
        else:
            print("Управление Redis отключено. Остановите и запустите Redis вручную во время теста.")

    print(f"Запускаю сценарий: {args.scenario}")
    print(f"Адрес API приложения: {args.api_base}")

    try:
        run_k6(
            k6_bin=args.k6_bin,
            api_base=args.api_base,
            scenario=args.scenario,
            summary_file=json_report,
            warmup_duration=args.warmup_duration,
            warmup_rate=args.warmup_rate,
            codes_file=args.codes_file,
            popular_codes_file=args.popular_codes_file,
            dashboard=True,
            html_report=html_report,
        )
    finally:
        if failover_controlled:
            subprocess.run(
                ["docker", "start", args.redis_container],
                cwd=SCRIPT_DIR,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                check=False,
            )

    print(f"Отчёт страницы: {html_report}")
    print(f"Сводка: {json_report}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print("Запуск прерван пользователем", file=sys.stderr)
        raise SystemExit(130)
    except subprocess.CalledProcessError as exc:
        command = " ".join(str(part) for part in exc.cmd) if exc.cmd else "команда"
        print(f"{command} завершилась с кодом {exc.returncode}", file=sys.stderr)
        raise SystemExit(exc.returncode)
    except Exception as exc:  # noqa: BLE001 - верхний уровень CLI
        print(f"Ошибка: {exc}", file=sys.stderr)
        raise SystemExit(1)
