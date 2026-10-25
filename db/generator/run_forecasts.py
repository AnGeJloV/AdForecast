#!/usr/bin/env python3
"""Прогон настоящих прогнозов по демо-кампаниям без ручных действий.

Идёт через тот же API (POST /campaigns/{id}/forecasts), что и кнопка
«Рассчитать», поэтому прогнозы получаются от реальной ML-модели.
Пропускает кампании, по которым завершённый прогноз уже есть.

Пример:
  python run_forecasts.py                      # локальный контейнер
  python run_forecasts.py --base https://host/api --password secret
"""

import argparse
import json
import urllib.error
import urllib.request


def req(base: str, path: str, method: str = "GET", body=None, token: str | None = None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(base + path, data=data, headers=headers, method=method)
    with urllib.request.urlopen(request) as response:
        raw = response.read().decode("utf-8")
        return response.status, (json.loads(raw) if raw else raw)


def main() -> None:
    parser = argparse.ArgumentParser(description="Прогнать настоящие прогнозы по демо-кампаниям")
    parser.add_argument("--base", default="http://localhost/api")
    parser.add_argument("--password", default="adforecastbsuir")
    parser.add_argument("--users", default="angejlov,demo,alena,maxim,olga,dmitry,ekaterina,sergey,irina")
    parser.add_argument("--refresh", action="store_true", help="пересчитывать, даже если прогноз уже есть")
    parser.add_argument("--active-duration", type=int, default=150, help="длительность для активных без данных")
    parser.add_argument("--planned-duration", type=int, default=90)
    args = parser.parse_args()

    created = skipped = errors = 0
    for login in (u.strip() for u in args.users.split(",") if u.strip()):
        try:
            _, raw = req(args.base, "/auth/login", "POST", {"login": login, "password": args.password})
            token = raw["accessToken"]
        except (urllib.error.HTTPError, KeyError) as exc:
            print(f"[{login}] вход не удался: {exc}")
            errors += 1
            continue

        try:
            _, campaigns = req(args.base, "/campaigns", token=token)
        except urllib.error.HTTPError as exc:
            print(f"[{login}] нет доступа к кампаниям: HTTP {exc.code}")
            errors += 1
            continue

        for campaign in campaigns:
            if campaign["status"] not in ("ACTIVE", "PLANNED"):
                continue
            cid = campaign["id"]
            if not args.refresh:
                try:
                    _, history = req(args.base, f"/campaigns/{cid}/forecasts", token=token)
                    if history and any(h.get("status") == "COMPLETED" for h in history):
                        skipped += 1
                        continue
                except urllib.error.HTTPError:
                    pass
            days = campaign.get("daysCount") or campaign.get("days") or 0
            if campaign["status"] == "PLANNED":
                duration = args.planned_duration
            else:
                duration = days + 30 if days else args.active_duration
            body = {"durationDays": duration,
                    "startMonth": 12 if campaign["status"] == "PLANNED" else None,
                    "modelScope": "BLEND"}
            try:
                status, result = req(args.base, f"/campaigns/{cid}/forecasts", "POST", body, token)
                if status == 201:
                    created += 1
                    print(f"  {login:9} {cid} {campaign['status']:7} -> {result.get('verdict')} "
                          f"ratio={result.get('paybackRatio')}")
                else:
                    errors += 1
            except urllib.error.HTTPError as exc:
                errors += 1
                print(f"  {login} {cid} {campaign['status']} -> HTTP {exc.code}: "
                      f"{exc.read().decode('utf-8', 'replace')[:100]}")

    print(f"\nСоздано прогнозов: {created}, пропущено (уже есть): {skipped}, ошибок: {errors}")


if __name__ == "__main__":
    main()
