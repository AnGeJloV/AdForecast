# db/generator

Генератор синтетических данных AdForecast.

## Генерация и загрузка данных

```bash
python generate.py
docker exec -i adforecast-db-1 psql -U adforecast -d adforecast -v ON_ERROR_STOP=1 < seed_data.sql
```

Параметры по умолчанию: 800 завершенных, 12 активных и 8 планируемых кампаний, около 37 тысяч дневных показателей, 6 CSV-выгрузок в `exports`. Полный список параметров: `python generate.py --help`.

Пароль пользователя БД берется из `deploy/.env`, при нестандартном значении замените `adforecast` в команде на `POSTGRES_USER`.

## Выгрузки рекламных кабинетов

`exports/*.csv` и `exports/*.xlsx` — файлы формата загрузки (FR-3), колонки: `date`, `impressions`, `clicks`, `cost`, `revenue`.

CSV создает `generate.py`. Для XLSX требуется `openpyxl`:

```bash
python csv_to_xlsx.py exports/sample_upload_1.csv exports/sample_upload_2.csv
```

## Датасет для обучения

`export_dataset.sh` выгружает из БД выборку обучающих записей (платформа, бюджет, длительность, месяц, коэффициент окупаемости) в `dataset.csv`:

```bash
bash export_dataset.sh
docker cp dataset.csv adforecast-ml-service-1:/tmp/dataset.csv
MSYS_NO_PATHCONV=1 docker exec adforecast-ml-service-1 python -m app.train_local /tmp/dataset.csv
```

## seed_data.sql

Файл результатов генерации, в репозиторий не попадает. Для воспроизведения запустите `generate.py` с тем же `--seed`.
