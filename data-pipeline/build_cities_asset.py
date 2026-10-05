#!/usr/bin/env python3
"""Writes app/src/main/assets/cities.tsv: the cities the app uses to turn a GPS
position into a city name, with no network.

One line per city: name, country, latitude, longitude, population, separated
by tabs. These are the same cities, under the same names, as the place packs
use ("Pharmacies in Nairobi"), so a position resolves to a name those packs know.

Source: GeoNames cities15000 and countryInfo (CC BY 4.0), fetched into
data-pipeline/work/sources/places/ if missing.

Usage:
  python data-pipeline/build_cities_asset.py
"""
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from build_places import load_cities  # noqa: E402

OUT = HERE.parent / "app" / "src" / "main" / "assets" / "cities.tsv"


def main():
    cities, countries = load_cities()
    lines = [f"{name}\t{countries.get(code, code)}\t{lat:.4f}\t{lon:.4f}\t{population}"
             for lat, lon, name, _ascii, code, population in sorted(cities, key=lambda c: (c[2], -c[5]))]
    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    print(f"{OUT}: {len(lines)} cities, {OUT.stat().st_size / 1e6:.2f} MB")


if __name__ == "__main__":
    main()
