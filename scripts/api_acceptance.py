"""API-приёмка AdForecast на живом стенде: сквозные проверки всех функций через REST.

Запуск (стенд поднят через docker compose up):
    python scripts/api_acceptance.py --base-url http://localhost

Скрипт работает под демо-маркетологом и администратором, создает временную кампанию
с выгрузкой и прогнозом и исключает ее из обучающей выборки, чтобы демо-данные
остались чистыми. Временный пользователь создается и блокируется.
"""

from __future__ import annotations

import argparse
import json
import time

import httpx

MARKETER = ("angejlov", "adforecastbsuir")
ADMIN = ("admin", "adforecastbsuir")

CHECKS: list[tuple[str, bool, str]] = []


def check(name: str, ok: bool, detail: str = "") -> None:
    CHECKS.append((name, ok, detail))
    mark = "OK  " if ok else "ПАДЕНИЕ"
    suffix = f" — {detail}" if detail and not ok else (f" ({detail})" if detail else "")
    print(f"[{mark}] {name}{suffix}")


class Api:
    def __init__(self, base_url: str):
        self.client = httpx.Client(base_url=base_url, timeout=120.0)
        self.token: str | None = None

    def login(self, login: str, password: str) -> httpx.Response:
        return self.client.post("/api/auth/login", json={"login": login, "password": password})

    def as_marketer(self, login: str, password: str) -> None:
        response = self.login(login, password)
        self.token = response.json()["accessToken"]

    def request(self, method: str, path: str, **kwargs) -> httpx.Response:
        headers = kwargs.pop("headers", {})
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        return self.client.request(method, path, headers=headers, **kwargs)

    def get(self, path: str, **kwargs) -> httpx.Response:
        return self.request("GET", path, **kwargs)

    def post(self, path: str, **kwargs) -> httpx.Response:
        return self.request("POST", path, **kwargs)

    def put(self, path: str, **kwargs) -> httpx.Response:
        return self.request("PUT", path, **kwargs)

    def delete(self, path: str, **kwargs) -> httpx.Response:
        return self.request("DELETE", path, **kwargs)


def csv_bytes(rows: int = 7, broken: bool = False) -> bytes:
    lines = ["date,impressions,clicks,cost,revenue"]
    for day in range(1, rows + 1):
        if broken and day == 4:
            lines.append(f"2026-10-{day:02d},abc,50,10.50,15.75")
        elif broken and day == 5:
            lines.append(f"2026-10-{day:02d},1200,60,-5,18")
        else:
            lines.append(f"2026-10-{day:02d},{1000 + day},{50 + day},{10 + day / 2:.2f},{15 + day:.2f}")
    return ("\n".join(lines) + "\n").encode("utf-8")


def run(base_url: str) -> None:
    started = time.perf_counter()
    run_suffix = str(int(time.time()))[-6:]
    marketer = Api(base_url)
    admin = Api(base_url)

    anonymous = Api(base_url)
    response = anonymous.get("/api/dashboard")
    check("запрос без токена отклонен", response.status_code == 401)

    response = marketer.login(*MARKETER)
    wrong = marketer.login(MARKETER[0], "неверный-пароль")
    check("вход с неверным паролем отклонен", wrong.status_code == 401)

    check("маркетолог входит в систему", response.status_code == 200
          and response.json()["role"] == "маркетолог")
    marketer.as_marketer(*MARKETER)
    response = admin.login(*ADMIN)
    check("администратор входит в систему", response.status_code == 200
          and response.json()["role"] == "администратор")
    admin.as_marketer(*ADMIN)

    me = marketer.get("/api/auth/me")
    me_body = me.json()
    check("профиль возвращает роль и полномочия",
          me.status_code == 200 and "campaign.manage" in me_body["permissions"]
          and me_body["organization"], f"роль {me_body.get('role')}")

    campaigns = marketer.get("/api/campaigns")
    check("маркетолог видит свои кампании", campaigns.status_code == 200
          and len(campaigns.json()) > 0, f"кампаний {len(campaigns.json())}")

    admin_campaigns = admin.get("/api/campaigns")
    check("администратору недоступны чужие кампании (пустой список)",
          admin_campaigns.status_code == 200 and len(admin_campaigns.json()) == 0)
    admin_create = admin.post("/api/campaigns", json={
        "accountId": 1, "campaignName": "админ", "budget": 1000, "currency": "BYN"})
    check("администратору запрещено управлять кампаниями", admin_create.status_code == 403)
    check("маркетологу недоступна админ-область",
          marketer.get("/api/admin/summary").status_code == 403)
    check("несуществующая кампания не выдает данные",
          marketer.get("/api/campaigns/999999").status_code == 403)

    accounts = marketer.get("/api/ad-accounts")
    accounts_body = accounts.json()
    if not accounts_body:
        own_account = marketer.post("/api/ad-accounts", json={
            "accountName": "Приемка API кабинет", "platform": "VK Реклама"})
        check("маркетолог заводит собственный кабинет", own_account.status_code == 201)
        accounts_body = [own_account.json()]
    else:
        check("рекламные кабинеты организации доступны",
              accounts.status_code == 200 and len(accounts_body) > 0, f"кабинетов {len(accounts_body)}")
    platforms = marketer.get("/api/ad-accounts/platforms")
    check("каталог площадок отдает минимальные бюджеты",
          platforms.status_code == 200 and len(platforms.json()) == 9)

    created = marketer.post("/api/campaigns", json={
        "accountId": accounts_body[0]["id"], "campaignName": "Приемка API — временная",
        "budget": 5000, "currency": "BYN"})
    check("кампания создается", created.status_code == 201, str(created.json())[:120])
    campaign_id = created.json()["id"]

    bad_currency = marketer.post("/api/campaigns", json={
        "accountId": accounts_body[0]["id"], "campaignName": "Приемка", "budget": 1000,
        "currency": "GBP"})
    check("неподдерживаемая валюта отклоняется", bad_currency.status_code == 400)
    zero_budget = marketer.post("/api/campaigns", json={
        "accountId": accounts_body[0]["id"], "campaignName": "Приемка", "budget": 0,
        "currency": "BYN"})
    check("неположительный бюджет отклоняется", zero_budget.status_code == 400)

    detail = marketer.get(f"/api/campaigns/{campaign_id}")
    check("карточка кампании открывается",
          detail.status_code == 200 and detail.json()["campaignName"] == "Приемка API — временная")
    activated = marketer.put(f"/api/campaigns/{campaign_id}", json={
        "campaignName": "Приемка API — временная", "budget": 5000, "currency": "BYN",
        "status": "ACTIVE"})
    check("кампания переводится в активные", activated.status_code == 200
          and activated.json()["status"] == "ACTIVE")

    valid_upload = marketer.post(f"/api/campaigns/{campaign_id}/uploads/analyze",
                                 files={"file": ("report.csv", csv_bytes(), "text/csv")})
    analysis = valid_upload.json()
    check("анализ выгрузки распознает колонки",
          valid_upload.status_code == 200 and analysis["rowCount"] == 7
          and analysis["dateFormat"] == "ГГГГ-ММ-ДД", str(analysis.get("errors")))
    committed = marketer.post(f"/api/campaigns/{campaign_id}/uploads",
                              files={"file": ("report.csv", csv_bytes(), "text/csv")})
    check("выгрузка загружается с числом строк",
          committed.status_code == 201 and committed.json()["rowsCount"] == 7)
    duplicate = marketer.post(f"/api/campaigns/{campaign_id}/uploads",
                              files={"file": ("report.csv", csv_bytes(), "text/csv")})
    check("повторная загрузка того же периода отклоняется", duplicate.status_code == 400)
    broken = marketer.post(f"/api/campaigns/{campaign_id}/uploads",
                           files={"file": ("broken.csv", csv_bytes(broken=True), "text/csv")})
    broken_errors = broken.json().get("errors", [])
    check("файл с некорректными строками дает построчные ошибки",
          broken.status_code == 400 and any("строка" in item for item in broken_errors),
          ("; ".join(broken_errors))[:120] or broken.text[:120])
    empty = marketer.post(f"/api/campaigns/{campaign_id}/uploads",
                          files={"file": ("empty.csv", b"", "text/csv")})
    check("пустой файл отклоняется", empty.status_code == 400)
    uploads = marketer.get(f"/api/campaigns/{campaign_id}/uploads")
    check("история загрузок показывает одну выгрузку",
          uploads.status_code == 200 and len(uploads.json()) == 1)
    metrics = marketer.get(f"/api/campaigns/{campaign_id}/metrics")
    check("дневные показатели кампании доступны", metrics.status_code == 200
          and len(metrics.json()) == 7)

    forecast_started = time.perf_counter()
    forecast = marketer.post(f"/api/campaigns/{campaign_id}/forecasts", json={
        "durationDays": 60, "startMonth": 12})
    forecast_seconds = time.perf_counter() - forecast_started
    forecast_body = forecast.json()
    check("прогноз формируется с вердиктом и интервалом",
          forecast.status_code == 201
          and forecast_body["verdict"] in ("PROFITABLE", "BORDERLINE", "UNPROFITABLE")
          and forecast_body["intervalLower"] <= forecast_body["paybackRatio"]
          <= forecast_body["intervalUpper"], str(forecast_body)[:160])
    check(f"время прогноза укладывается в 60 с ({forecast_seconds:.1f} с)",
          forecast_seconds < 60.0)
    forecast_id = forecast_body["id"]

    zero_duration = marketer.post(f"/api/campaigns/{campaign_id}/forecasts", json={
        "durationDays": 0})
    check("некорректная длительность отклоняется", zero_duration.status_code == 400)

    finished_campaigns = [c for c in campaigns.json() if c["status"] == "FINISHED"]
    if finished_campaigns:
        finished_id = finished_campaigns[0]["id"]
        rejected = marketer.post(f"/api/campaigns/{finished_id}/forecasts", json={
            "durationDays": 60})
        check("прогноз по завершенной кампании отклоняется", rejected.status_code == 400)
    else:
        check("прогноз по завершенной кампании отклоняется", False, "нет завершенных кампаний")

    history = marketer.get(f"/api/campaigns/{campaign_id}/forecasts")
    check("история прогнозов кампании ведется", history.status_code == 200
          and len(history.json()) >= 1)
    all_forecasts = marketer.get("/api/forecasts")
    check("общая история прогнозов доступна", all_forecasts.status_code == 200)

    comparison = marketer.post("/api/forecasts/compare", json={
        "variants": [
            {"platform": "VK Реклама", "currency": "BYN", "budget": 5000, "durationDays": 60,
             "startMonth": 12},
            {"platform": "Email-платформа", "budget": 3000, "durationDays": 30, "startMonth": 12},
        ]})
    comparison_body = comparison.json()
    check("сравнение вариантов возвращает вердикты",
          comparison.status_code == 200 and len(comparison_body) == 2
          and all("verdict" in variant for variant in comparison_body))
    unknown_platform = marketer.post("/api/forecasts/compare", json={
        "variants": [{"platform": "Авито", "budget": 5000, "durationDays": 60, "startMonth": 12}]})
    check("сравнение по неизвестной площадке отклоняется", unknown_platform.status_code == 400)
    bad_currency_compare = marketer.post("/api/forecasts/compare", json={
        "variants": [{"platform": "VK Реклама", "currency": "GBP", "budget": 5000,
                      "durationDays": 60, "startMonth": 12}]})
    check("сравнение с неподдерживаемой валютой отклоняется", bad_currency_compare.status_code == 400)

    xlsx = marketer.get(f"/api/forecasts/{forecast_id}/export/xlsx")
    check("экспорт XLSX содержит книгу Excel", xlsx.status_code == 200
          and xlsx.content[:2] == b"PK")
    pdf = marketer.get(f"/api/forecasts/{forecast_id}/export/pdf")
    check("экспорт PDF содержит документ", pdf.status_code == 200
          and pdf.content[:4] == b"%PDF")
    missing_export = marketer.get("/api/forecasts/999999/export/pdf")
    check("экспорт несуществующего прогноза дает 404", missing_export.status_code == 404)

    dashboard = marketer.get("/api/dashboard")
    check("дашборд отдает сводные показатели", dashboard.status_code == 200
          and dashboard.json()["campaignsTotal"] >= 1)
    cashflow = marketer.get("/api/dashboard/cashflow", params={"granularity": "week"})
    check("денежный поток строится по неделям", cashflow.status_code == 200
          and isinstance(cashflow.json(), list))

    summary = admin.get("/api/admin/summary")
    summary_body = summary.json()
    check("сводка по системе доступна администратору", summary.status_code == 200
          and summary_body["usersTotal"] >= 2 and summary_body["mlServiceAvailable"])
    activity = admin.get("/api/admin/activity", params={
        "from": "2026-01-01T00:00:00+03:00", "to": "2026-12-31T23:59:59+03:00"})
    check("активность фильтруется по периоду", activity.status_code == 200
          and activity.json()["total"] > 0)
    periods = admin.get("/api/admin/activity/periods", params={"unit": "day"})
    bad_period = admin.get("/api/admin/activity/periods", params={"unit": "decade"})
    check("периоды активности дают день и отклоняют неизвестный тип",
          periods.status_code == 200 and bad_period.status_code == 400)
    audit = admin.get("/api/admin/audit")
    audit_body = audit.json()
    audit_entries = audit_body.get("content", audit_body)
    check("журнал действий содержит записи", audit.status_code == 200 and len(audit_entries) > 0,
          f"записей {len(audit_entries)}")

    models = admin.get("/api/admin/models")
    models_body = models.json()
    check("реестр моделей доступен администратору", models.status_code == 200
          and len(models_body["models"]) >= 1
          and models_body["organizationsTotal"] >= 1)
    preview = admin.get("/api/admin/training/preview")
    check("предпросмотр обучающей выборки работает", preview.status_code == 200
          and preview.json()["campaignCount"] > 0)

    users = admin.get("/api/users")
    roles = admin.get("/api/users/roles")
    users_body = users.json()
    users_entries = users_body.get("content", users_body) if isinstance(users_body, dict) else users_body
    check("список пользователей и ролей доступен", users.status_code == 200
          and roles.status_code == 200 and len(roles.json()) >= 2,
          f"users {users.status_code}, roles {roles.status_code}")
    temp_login = f"api_acc_{run_suffix}"
    new_user = admin.post("/api/users", json={
        "login": temp_login, "email": f"api-acc-{run_suffix}@example.com",
        "fullName": "Приемка API", "password": "пароль-приемки-123", "roleId": roles.json()[0]["id"]})
    check("администратор создает пользователя", new_user.status_code == 201)
    duplicate_user = admin.post("/api/users", json={
        "login": temp_login, "email": f"api-acc-{run_suffix}@example.com",
        "fullName": "Приемка API", "password": "пароль-приемки-123", "roleId": roles.json()[0]["id"]})
    check("дубликат учетной записи отклоняется", duplicate_user.status_code == 400)
    self_id = next(u["id"] for u in users_entries if u["login"] == ADMIN[0])
    self_block = admin.put(f"/api/users/{self_id}", json={
        "email": "admin@example.com", "fullName": "Администратор", "roleId": roles.json()[0]["id"],
        "isActive": False})
    check("самоблокировка администратора отклоняется", self_block.status_code == 400)
    users_after = admin.get("/api/users").json()
    users_after = users_after.get("content", users_after) if isinstance(users_after, dict) else users_after
    temp_user = next((u for u in users_after if u["login"] == temp_login), None)
    if temp_user:
        blocked = admin.put(f"/api/users/{temp_user['id']}", json={
            "email": f"api-acc-{run_suffix}@example.com", "fullName": "Приемка API",
            "roleId": roles.json()[0]["id"], "isActive": False})
        blocked_login = Api(base_url).login(temp_login, "пароль-приемки-123")
        check("заблокированный пользователь не входит",
              blocked.status_code == 200 and blocked_login.status_code == 401)
    else:
        check("заблокированный пользователь не входит", False, "учетная запись не создана")

    admin_view = admin.get(f"/api/admin/campaigns/{campaign_id}")
    admin_view_body = admin_view.json()
    check("администратор просматривает карточку кампании в режиме чтения",
          admin_view.status_code == 200
          and admin_view_body["campaign"]["campaignName"] == "Приемка API — временная"
          and isinstance(admin_view_body["metrics"], list))
    marketer_view = marketer.get(f"/api/admin/campaigns/{campaign_id}")
    check("маркетологу недоступен админский просмотр кампании",
          marketer_view.status_code == 403)

    flags = admin.put(f"/api/admin/training/campaigns/{campaign_id}/flags", json={
        "inTraining": False, "isTest": True, "reason": "приемка API: временная кампания"})
    check("временная кампания исключена из обучающей выборки", flags.status_code == 200)

    print()
    passed = sum(1 for _, ok, _ in CHECKS if ok)
    print(f"ИТОГ: {passed} из {len(CHECKS)} проверок пройдено за {time.perf_counter() - started:.1f} с")
    if passed != len(CHECKS):
        print("Проверки с падениями:")
        for name, ok, detail in CHECKS:
            if not ok:
                print(f"  - {name}: {detail}")
    raise SystemExit(0 if passed == len(CHECKS) else 1)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="API-приемка AdForecast")
    parser.add_argument("--base-url", default="http://localhost")
    args = parser.parse_args()
    run(args.base_url)