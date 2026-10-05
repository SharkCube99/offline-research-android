#!/usr/bin/env python3
"""Fetches the places a traveller looks for (places to eat and sleep, pharmacies,
hospitals, banks, shops for daily needs, stations) from Overture Maps and keeps
the few columns the places pack needs.

Source: Overture Maps Foundation, places theme, release 2026-09-23.1, read
straight from its public storage. About 81 million places in 11 GB of Parquet
files; only the needed columns of the needed kinds are transferred, and the
result is written to data-pipeline/work/sources/overture/places/ (one file
per source file, so an interrupted run continues where it stopped).

Licence of the data: CDLA-Permissive-2.0 for the places theme as a whole; each
place also names its own source and licence, which are kept.

Needs the duckdb package (pip install duckdb; MIT licence), on the build
computer only.

Usage:
  python data-pipeline/fetch_overture_places.py
"""
import re
import sys
import time
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
OUT = HERE / "work" / "sources" / "overture" / "places"
RELEASE = "2026-09-23.1"
BUCKET = "https://overturemaps-us-west-2.s3.amazonaws.com"
PREFIX = f"release/{RELEASE}/theme=places/type=place/"

# Whole groups (Overture's basic_category) ...
GROUPS = [
    "restaurant", "fast_food_restaurant", "cafe", "coffee_shop", "bar",
    "pharmacy_and_drug_store", "hospital", "emergency_or_urgent_care_facility", "emergency_department",
    "primary_care_or_general_clinic", "dental_clinic",
    "atm", "bank_or_credit_union",
    "hotel", "bed_and_breakfast", "resort", "inn", "campground",
    "convenience_store", "shopping_mall", "department_store", "market", "farmers_market",
    "train_station", "airport", "gas_station", "ev_charging_station",
    "police_station", "embassy", "museum", "library", "public_restroom",
]
# ... and single kinds (taxonomy.primary) out of groups that are otherwise too broad.
KINDS = [
    "bakery", "diner", "sandwich_shop", "ice_cream_shop", "tapas_bar", "gastropub", "bistro", "delicatessen",
    "grocery_store", "organic_grocery_store", "health_food_store",
    "hostel", "lodging", "lodge",
    "bus_station", "metro_station", "light_rail_and_subway_station", "ferry_service",
    "currency_exchange", "laundromat", "post_office", "bookstore", "urgent_care_clinic", "walk_in_clinic",
]
# Below this Overture itself is unsure the place exists.
MIN_CONFIDENCE = 0.6


def source_files():
    with urllib.request.urlopen(f"{BUCKET}/?prefix={PREFIX}&max-keys=1000", timeout=60) as response:
        listing = response.read().decode("utf-8")
    return sorted(re.findall(r"<Key>([^<]+\.parquet)</Key>", listing))


def keep_awake():
    """Asks Windows not to sleep while the download runs; it takes an hour or more."""
    if sys.platform == "win32":
        import ctypes
        ctypes.windll.kernel32.SetThreadExecutionState(0x80000000 | 0x00000001 | 0x00000040)


def main():
    try:
        import duckdb
    except ImportError:
        sys.exit("duckdb is not installed. Run: pip install duckdb")
    keep_awake()
    OUT.mkdir(parents=True, exist_ok=True)
    con = duckdb.connect()
    con.execute("INSTALL httpfs; LOAD httpfs; SET s3_region='us-west-2'; SET http_retries=8; SET http_retry_wait_ms=2000;")
    groups = ", ".join(f"'{g}'" for g in GROUPS)
    kinds = ", ".join(f"'{k}'" for k in KINDS)
    files = source_files()
    print(f"{len(files)} source files", flush=True)
    for number, key in enumerate(files, 1):
        dest = OUT / (Path(key).name.split("-")[1] + ".parquet")
        if dest.exists():
            print(f"{number}/{len(files)} already here", flush=True)
            continue
        started = time.time()
        tmp = dest.with_suffix(".part")
        con.execute(f"""
            COPY (
              SELECT names."primary" AS name,
                     basic_category AS grp,
                     taxonomy."primary" AS kind,
                     round(confidence, 2) AS confidence,
                     addresses[1].freeform AS street,
                     addresses[1].locality AS locality,
                     addresses[1].postcode AS postcode,
                     addresses[1].region AS region,
                     addresses[1].country AS country,
                     round((bbox.xmin + bbox.xmax) / 2, 5) AS lon,
                     round((bbox.ymin + bbox.ymax) / 2, 5) AS lat,
                     phones[1] AS phone,
                     websites[1] AS website,
                     operating_status AS status,
                     sources[1].dataset AS dataset,
                     sources[1].update_time AS updated
              FROM read_parquet('s3://overturemaps-us-west-2/{key}')
              WHERE (basic_category IN ({groups}) OR taxonomy."primary" IN ({kinds}))
                AND names."primary" IS NOT NULL
                AND confidence >= {MIN_CONFIDENCE}
                AND coalesce(operating_status, 'open') = 'open'
            ) TO '{tmp.as_posix()}' (FORMAT parquet, COMPRESSION zstd)""")
        tmp.replace(dest)
        rows = con.execute(f"SELECT count(*) FROM '{dest.as_posix()}'").fetchone()[0]
        print(f"{number}/{len(files)} {dest.name}: {rows:,} places, {dest.stat().st_size / 1e6:.0f} MB, "
              f"{time.time() - started:.0f} s", flush=True)
    total = con.execute(f"SELECT count(*) FROM '{OUT.as_posix()}/*.parquet'").fetchone()[0]
    print(f"done: {total:,} places in {sum(p.stat().st_size for p in OUT.glob('*.parquet')) / 1e9:.2f} GB", flush=True)


if __name__ == "__main__":
    main()
