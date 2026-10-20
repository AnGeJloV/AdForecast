import argparse
import csv
from pathlib import Path

import openpyxl


def main() -> None:
    parser = argparse.ArgumentParser(description="Конвертация CSV-выгрузок в XLSX")
    parser.add_argument("csv_files", nargs="+", help="пути к CSV-файлам")
    args = parser.parse_args()
    for path in map(Path, args.csv_files):
        workbook = openpyxl.Workbook()
        sheet = workbook.active
        sheet.title = "stats"
        with path.open(encoding="utf-8") as f:
            for row in csv.reader(f):
                sheet.append(row)
        target = path.with_suffix(".xlsx")
        workbook.save(target)
        print(target)


if __name__ == "__main__":
    main()
