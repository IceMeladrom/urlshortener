#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Универсальный скрипт для проведения нагрузочного тестирования URL Shortener.
Поддерживает два режима генерации данных:
- через API (медленно, не требует прямого доступа к БД)
- через прямой SQL в Docker (быстро, требует docker и контейнер shortener-db)
"""

import argparse
import json
import os
import random
import string
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import tempfile
from pathlib import Path

# Цвета для вывода (ANSI)
GREEN = '\033[0;32m' if sys.platform != 'win32' else ''
YELLOW = '\033[1;33m' if sys.platform != 'win32' else ''
RED = '\033[0;31m' if sys.platform != 'win32' else ''
NC = '\033[0m' if sys.platform != 'win32' else ''  # No Color


def print_color(msg, color):
    """Печатает сообщение с цветом, если поддерживается."""
    if color and sys.platform != 'win32':
        print(f"{color}{msg}{NC}")
    else:
        print(msg)


def check_command(cmd):
    """Проверяет наличие команды в системе."""
    try:
        subprocess.run([cmd, '--version'], capture_output=True, check=True)
        return True
    except (subprocess.SubprocessError, FileNotFoundError):
        return False


def check_service(base_url):
    """
    Проверяет доступность сервиса через actuator/health.
    Возвращает True, если получен код 200.
    """
    try:
        req = urllib.request.Request(f"{base_url}/actuator/health", method='GET')
        with urllib.request.urlopen(req, timeout=5) as resp:
            return resp.getcode() == 200
    except Exception:
        return False


def generate_links_api(num, base_url, output_file):
    """
    Генерирует заданное количество коротких ссылок через API.
    Возвращает количество успешно созданных ссылок.
    """
    created = 0
    with open(output_file, 'w', encoding='utf-8') as f:
        for i in range(1, num + 1):
            # Генерируем случайный URL
            rand_str = ''.join(random.choices(string.ascii_letters + string.digits, k=10))
            url = f"https://example.com/{rand_str}"
            data = json.dumps({"url": url}).encode('utf-8')
            req = urllib.request.Request(f"{base_url}/api/v1/shorten", data=data,
                                         headers={'Content-Type': 'application/json'},
                                         method='POST')
            try:
                with urllib.request.urlopen(req, timeout=5) as resp:
                    if resp.getcode() == 200:
                        resp_data = json.loads(resp.read().decode('utf-8'))
                        short_code = resp_data.get('shortCode')
                        if short_code:
                            f.write(short_code + '\n')
                            created += 1
                    else:
                        print_color(f"Неожиданный код ответа: {resp.getcode()}", RED)
            except Exception as e:
                print_color(f"Ошибка при создании ссылки {i}: {e}", RED)

            if i % 100 == 0:
                print_color(f"Создано {i} ссылок...", YELLOW)
            # Небольшая задержка для снижения нагрузки
            time.sleep(0.01)

    return created


def generate_links_sql_fast(num, output_file):
    """
    Быстрая генерация ссылок через прямой SQL в Docker.
    Коды генерируются как 'code' + порядковый номер, что гарантирует уникальность.
    Возвращает количество созданных записей (должно совпадать с num).
    """
    if not check_command('docker'):
        print_color("Docker не найден. Невозможно использовать быстрый режим.", RED)
        return 0

    try:
        subprocess.run(['docker', 'inspect', 'shortener-db'], capture_output=True, check=True)
    except subprocess.SubprocessError:
        print_color("Контейнер shortener-db не найден или не запущен.", RED)
        return 0

    print_color(f"Генерация {num} ссылок через прямой SQL...", YELLOW)

    # SQL-скрипт с уникальными кодами на основе счётчика
    sql_content = """
DO $$
DECLARE
    batch_size INT := 1000;
    total_batches INT := ceil(%s::numeric / batch_size);
    start_id INT;
BEGIN
    FOR b IN 0..total_batches-1 LOOP
        start_id := b * batch_size + 1;
        INSERT INTO links (short_code, original_url, created_at, expires_at, click_count)
        SELECT
            'code' || LPAD((start_id + g - 1)::text, 7, '0'),
            'https://example.com/' || md5(random()::text),
            now(),
            now() + interval '7 days',
            0
        FROM generate_series(1, LEAST(batch_size, %s - b*batch_size)) AS g;
        PERFORM pg_sleep(0.01);
    END LOOP;
END $$;

-- extract generated short codes
COPY (SELECT short_code FROM links ORDER BY id DESC LIMIT %s) TO '%s';
""" % (num, num, num, '/tmp/shortcodes.txt')

    # Создаём временный файл с явной кодировкой UTF-8
    with tempfile.NamedTemporaryFile(mode='w', suffix='.sql', encoding='utf-8', delete=False) as tmp:
        tmp.write(sql_content)
        sql_file = tmp.name

    try:
        # Копируем в контейнер
        subprocess.run(['docker', 'cp', sql_file, 'shortener-db:/tmp/generate.sql'], check=True, capture_output=True)
    except subprocess.SubprocessError as e:
        err = e.stderr.decode() if e.stderr else str(e)
        print_color(f"Ошибка копирования SQL в контейнер: {err}", RED)
        os.unlink(sql_file)
        return 0

    # Выполняем SQL и захватываем вывод ошибок
    try:
        result = subprocess.run([
            'docker', 'exec', '-i', 'shortener-db',
            'psql', '-U', 'postgres', '-d', 'shortener', '-f', '/tmp/generate.sql'
        ], check=True, capture_output=True, text=True)
        if result.stderr:
            print_color(f"Предупреждения SQL: {result.stderr}", YELLOW)
    except subprocess.CalledProcessError as e:
        print_color(f"Ошибка выполнения SQL: {e.stderr}", RED)
        os.unlink(sql_file)
        return 0

    # Копируем обратно файл с кодами
    try:
        subprocess.run(['docker', 'cp', 'shortener-db:/tmp/shortcodes.txt', output_file], check=True,
                       capture_output=True)
    except subprocess.SubprocessError as e:
        err = e.stderr.decode() if e.stderr else str(e)
        print_color(f"Ошибка копирования файла с кодами: {err}", RED)
        os.unlink(sql_file)
        return 0

    # Очистка
    try:
        subprocess.run(['docker', 'exec', 'shortener-db', 'rm', '/tmp/generate.sql', '/tmp/shortcodes.txt'],
                       check=False)
    except:
        pass

    os.unlink(sql_file)

    # Подсчёт
    try:
        with open(output_file, 'r', encoding='utf-8') as f:
            lines = f.readlines()
        created = len(lines)
    except Exception as e:
        print_color(f"Ошибка чтения файла с кодами: {e}", RED)
        created = 0

    print_color(f"SQL-генерация завершена, создано {created} записей.", GREEN)
    return created


def run_k6(script_file, duration=None, vus=None, rps=None):
    """Запускает k6 с указанным скриптом и параметрами."""
    cmd = ['k6', 'run']
    if duration:
        cmd.extend(['--duration', duration])
    if vus:
        cmd.extend(['--vus', str(vus)])
    if rps:
        cmd.extend(['--rps', str(rps)])
    cmd.append(script_file)

    print_color(f"Запуск: {' '.join(cmd)}", YELLOW)
    result = subprocess.run(cmd, capture_output=False)
    return result.returncode == 0


def main():
    parser = argparse.ArgumentParser(
        description='Автоматизация нагрузочного тестирования URL Shortener'
    )
    parser.add_argument('--base-url', default='http://localhost:8080',
                        help='Базовый URL сервиса (по умолчанию: http://localhost:8080)')
    parser.add_argument('--num-links', type=int, default=50000,
                        help='Количество ссылок для генерации (по умолчанию: 50000)')
    parser.add_argument('--warmup-duration', default='5m',
                        help='Длительность прогрева в формате k6 (по умолчанию: 5m)')
    parser.add_argument('--warmup-rps', type=int, default=50,
                        help='RPS во время прогрева (по умолчанию: 50)')
    parser.add_argument('--load-script', default='mixed.js',
                        help='Имя файла сценария основного теста (по умолчанию: mixed.js)')
    parser.add_argument('--warmup-script', default='warmup.js',
                        help='Имя файла сценария прогрева (по умолчанию: warmup.js)')
    parser.add_argument('--codes-file', default='shortcodes.txt',
                        help='Файл для сохранения сгенерированных кодов (по умолчанию: shortcodes.txt)')
    parser.add_argument('--skip-generate', action='store_true',
                        help='Пропустить генерацию данных (использовать существующий файл с кодами)')
    parser.add_argument('--skip-warmup', action='store_true',
                        help='Пропустить прогрев кэша')
    parser.add_argument('--skip-load', action='store_true',
                        help='Пропустить прогрев и основной нагрузочный тест (только генерация данных)')
    parser.add_argument('--fast-mode', action='store_true',
                        help='Использовать быструю генерацию через прямой SQL (требуется Docker)')
    args = parser.parse_args()

    print_color("=" * 60, GREEN)
    print_color("ЗАПУСК НАГРУЗОЧНОГО ТЕСТИРОВАНИЯ URL SHORTENER", GREEN)
    print_color("=" * 60, GREEN)

    # 1. Проверка наличия k6
    print_color("\n[1] Проверка наличия k6...", YELLOW)
    if not check_command('k6'):
        print_color("ОШИБКА: k6 не найден. Установите k6 (https://k6.io/docs/get-started/installation/)", RED)
        sys.exit(1)
    print_color("k6 найден.", GREEN)

    # 2. Проверка доступности сервиса через actuator/health
    print_color("\n[2] Проверка доступности сервиса...", YELLOW)
    if not check_service(args.base_url):
        print_color(
            f"ОШИБКА: Сервис {args.base_url} недоступен (проверен /actuator/health). Убедитесь, что docker-compose запущен.",
            RED)
        sys.exit(1)
    print_color(f"Сервис {args.base_url} доступен.", GREEN)

    # 3. Генерация данных
    if not args.skip_generate:
        print_color("\n[3] Генерация данных...", YELLOW)
        start_time = time.time()
        if args.fast_mode:
            print_color("Быстрый режим: прямая вставка в БД через Docker.", YELLOW)
            created = generate_links_sql_fast(args.num_links, args.codes_file)
        else:
            print_color("Медленный режим: через API. Это может занять много времени.", YELLOW)
            created = generate_links_api(args.num_links, args.base_url, args.codes_file)
        elapsed = time.time() - start_time
        if created == 0:
            print_color("Не удалось создать ни одной ссылки. Проверьте логи.", RED)
            sys.exit(1)
        print_color(f"Создано {created} ссылок за {elapsed:.2f} секунд. Файл: {args.codes_file}", GREEN)
    else:
        print_color("\n[3] Пропуск генерации данных. Используем существующий файл.", YELLOW)
        if not Path(args.codes_file).exists():
            print_color(f"Файл {args.codes_file} не найден. Отмена.", RED)
            sys.exit(1)

    # 4. Прогрев кэша
    if not args.skip_warmup and not args.skip_load:
        print_color("\n[4] Прогрев кэша...", YELLOW)
        print_color(f"Запуск {args.warmup_script} с длительностью {args.warmup_duration}, RPS {args.warmup_rps}",
                    YELLOW)
        if not run_k6(args.warmup_script, duration=args.warmup_duration, vus=10, rps=args.warmup_rps):
            print_color("Прогрев кэша завершился с ошибкой.", RED)
            sys.exit(1)
        print_color("Прогрев кэша завершен.", GREEN)
    else:
        if args.skip_load:
            print_color("\n[4] Пропуск прогрева и основного теста (--skip-load).", YELLOW)
        elif args.skip_warmup:
            print_color("\n[4] Пропуск прогрева (--skip-warmup).", YELLOW)

    # 5. Основной нагрузочный тест
    if not args.skip_load:
        print_color("\n[5] Запуск основного нагрузочного теста...", YELLOW)
        print_color(f"Сценарий: {args.load_script}", YELLOW)
        if not run_k6(args.load_script):
            print_color("Основной тест завершился с ошибкой.", RED)
            sys.exit(1)

        print_color("\n" + "=" * 60, GREEN)
        print_color("ВСЕ ТЕСТЫ УСПЕШНО ЗАВЕРШЕНЫ", GREEN)
        print_color("=" * 60, GREEN)
    else:
        print_color("\n[5] Пропуск основного теста (--skip-load).", YELLOW)


if __name__ == '__main__':
    main()
