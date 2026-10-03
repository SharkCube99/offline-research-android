#!/usr/bin/env python3
"""Builds places.db: vegan and vegetarian places to eat, worldwide, grouped by
city, in the same format as the Wikipedia index so the app can cite them.

Sources (fetched into data-pipeline/work/sources/places/ if missing):
  OpenStreetMap, through the Overpass API: eating places tagged as vegan or
    vegetarian. (c) OpenStreetMap contributors, Open Database Licence (ODbL) 1.0.
  GeoNames cities15000: cities of 15,000 people or more, to say which city a
    place belongs to. CC BY 4.0.

Usage:
  python data-pipeline/build_places.py [--out data-pipeline/work/index/places.db]

What the result is and is not:
  - One entry per city and diet: "Vegan restaurants in Berlin", "Vegetarian
    restaurants in Berlin". A place belongs to the largest city within 15 km,
    or failing that to the nearest city within 50 km.
  - Fully vegan (or vegetarian) places come first, then places that only offer
    such dishes. Within a group, entries with more details come first. There
    are no ratings in the data, so nothing here says which place is best.
  - It is a snapshot. Places close and the map is not always told.
  - Places that merely offer vegetarian options (diet:vegetarian=yes) are left
    out: there are several hundred thousand of them.
"""
import argparse
import datetime
import io
import json
import math
import re
import sys
import urllib.parse
import urllib.request
import zipfile
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from docindex import write_index  # noqa: E402

SOURCES = HERE / "work" / "sources" / "places"
# Tried in turn; public Overpass servers are often busy.
OVERPASS = ["https://maps.mail.ru/osm/tools/overpass/api/interpreter",
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter"]
GEONAMES = "https://download.geonames.org/export/dump/cities15000.zip"
COUNTRIES = "https://download.geonames.org/export/dump/countryInfo.txt"
USER_AGENT = "offline-research-index-builder/0.1 (one-off places build)"
AMENITIES = "restaurant|cafe|fast_food|ice_cream|food_court|pub|bar|biergarten"
# One exact tag per request. Exact tags are looked up in the server's index;
# a pattern over a region makes it scan, and the public servers refuse that.
# The kind of place (restaurant, café, ...) is filtered here afterwards.
TAGS = [("diet:vegan", "only"), ("diet:vegan", "yes"), ("diet:vegetarian", "only"),
        ("cuisine", "vegan"), ("cuisine", "vegetarian")]
# A modest time limit and the default memory limit: servers hold back requests that reserve a lot.
QUERY = '[out:json][timeout:240];nwr["{key}"="{value}"]["name"];out center tags;'
PLACES_PER_PASSAGE = 8
KIND = {"restaurant": "restaurant", "cafe": "café", "fast_food": "fast-food place", "ice_cream": "ice-cream shop",
        "food_court": "food court", "pub": "pub", "bar": "bar", "biergarten": "beer garden"}


def fetch(url, dest, data=None):
    if dest.exists():
        return
    dest.parent.mkdir(parents=True, exist_ok=True)
    print(f"fetching {url}", flush=True)
    request = urllib.request.Request(url, data=data, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=1800) as response:
        content = response.read()
    dest.write_bytes(content)
    print(f"  {len(content) / 1e6:.1f} MB", flush=True)


def fetch_places():
    """One file per tag, so an interrupted run continues. Returns (elements, snapshot date)."""
    import time
    wanted = set(AMENITIES.split("|"))
    elements, seen, snapshot = [], set(), ""
    for key, value in TAGS:
        dest = SOURCES / "overpass" / f"{key.replace(':', '_')}_{value}.json"
        if not dest.exists():
            body = urllib.parse.urlencode({"data": QUERY.format(key=key, value=value)}).encode()
            for attempt in range(12):
                server = OVERPASS[attempt % len(OVERPASS)]
                try:
                    request = urllib.request.Request(server, data=body, headers={"User-Agent": USER_AGENT})
                    with urllib.request.urlopen(request, timeout=900) as response:
                        content = response.read()
                    json.loads(content)  # a "server too busy" page is not JSON
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    dest.write_bytes(content)
                    print(f"{key}={value}: {len(content) / 1e6:.1f} MB from {server}", flush=True)
                    time.sleep(10)  # be a light user of a shared service
                    break
                except Exception as error:  # busy server, timeout, error page
                    print(f"{key}={value}: {type(error).__name__} {str(error)[:80]} at {server}; waiting", flush=True)
                    time.sleep(60)
            else:
                raise SystemExit(f"could not fetch {key}={value}; run the script again later to continue")
        payload = json.loads(dest.read_text(encoding="utf-8"))
        snapshot = max(snapshot, payload.get("osm3s", {}).get("timestamp_osm_base", "")[:10])
        for element in payload["elements"]:
            identity = (element["type"], element["id"])
            if identity not in seen and element.get("tags", {}).get("amenity") in wanted:
                seen.add(identity)
                elements.append(element)
    return elements, snapshot or datetime.date.today().isoformat()


def load_cities():
    """[(lat, lon, name, ascii name, country code, population)], without city districts."""
    fetch(GEONAMES, SOURCES / "cities15000.zip")
    fetch(COUNTRIES, SOURCES / "countryInfo.txt")
    countries = {}
    for line in (SOURCES / "countryInfo.txt").read_text(encoding="utf-8").splitlines():
        if line and not line.startswith("#"):
            parts = line.split("\t")
            countries[parts[0]] = parts[4]
    cities = []
    with zipfile.ZipFile(SOURCES / "cities15000.zip") as archive:
        for line in io.TextIOWrapper(archive.open("cities15000.txt"), encoding="utf-8"):
            f = line.rstrip("\n").split("\t")
            if f[7] == "PPLX":  # a section of a city, such as a borough
                continue
            cities.append((float(f[4]), float(f[5]), f[1], f[2], f[8], int(f[14] or 0)))
    return cities, countries


def distance_km(lat1, lon1, lat2, lon2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    a = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(math.radians(lon2 - lon1) / 2) ** 2
    return 12742 * math.asin(math.sqrt(a))


class CityFinder:
    """The largest city within 15 km of a point, or the nearest within 50 km."""

    def __init__(self, cities):
        self.cities = cities
        self.grid = defaultdict(list)
        for index, city in enumerate(cities):
            self.grid[(math.floor(city[0]), math.floor(city[1]))].append(index)

    def find(self, lat, lon):
        near = []
        # 50 km is under one degree of latitude; longitude cells shrink toward the poles.
        reach = 1 if abs(lat) < 60 else 3
        for dlat in (-1, 0, 1):
            for dlon in range(-reach, reach + 1):
                for index in self.grid.get((math.floor(lat) + dlat, math.floor(lon) + dlon), ()):
                    city = self.cities[index]
                    km = distance_km(lat, lon, city[0], city[1])
                    if km <= 50:
                        near.append((km, index))
        if not near:
            return None
        close = [item for item in near if item[0] <= 15]
        if close:
            return max(close, key=lambda item: self.cities[item[1]][5])[1]
        return min(near)[1]


def clean(value):
    return re.sub(r"\s+", " ", value.replace(";", ", ")).strip(" ,.")


def describe(tags, diet, full):
    """One sentence about a place. It ends with a full stop and has none inside
    that could be taken for a sentence end, so the app keeps it whole."""
    kind = KIND.get(tags.get("amenity"), "place to eat")
    name = clean(tags["name"]).replace(". ", " ")
    parts = [f"{name} is a {'fully ' + diet if full else diet + '-friendly'} {kind}"]
    cuisine = [c.strip().replace("_", " ") for c in tags.get("cuisine", "").split(";")
               if c.strip() and c.strip() not in ("vegan", "vegetarian")]
    if cuisine:
        parts.append("cuisine: " + ", ".join(cuisine[:3]))
    street = " ".join(v for v in (tags.get("addr:street"), tags.get("addr:housenumber")) if v)
    address = ", ".join(clean(v) for v in (street, tags.get("addr:suburb"), tags.get("addr:postcode")) if v)
    if address:
        parts.append("address: " + address.replace(". ", " "))
    # Opening hours and websites are left out: they double the length of an entry,
    # and the prompt has room for only a few hundred words of sources.
    return "; ".join(parts) + "."


def detail(tags):
    """More details mean a better-kept entry; such entries are listed first."""
    return sum(1 for key in ("addr:street", "opening_hours", "website", "contact:website", "phone", "contact:phone", "cuisine") if tags.get(key))


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--out", type=Path, default=HERE / "work" / "index" / "places.db")
    args = parser.parse_args()

    elements, snapshot = fetch_places()
    cities, countries = load_cities()
    finder = CityFinder(cities)

    by_city = defaultdict(list)
    skipped = 0
    for element in elements:
        tags = element.get("tags", {})
        lat = element.get("lat", element.get("center", {}).get("lat"))
        lon = element.get("lon", element.get("center", {}).get("lon"))
        if lat is None or not tags.get("name") or tags.get("disused") == "yes" or "disused:amenity" in tags:
            skipped += 1
            continue
        city = finder.find(lat, lon)
        if city is None:
            skipped += 1
            continue
        cuisine = tags.get("cuisine", "")
        vegan_only = tags.get("diet:vegan") == "only" or re.search(r"(^|;)\s*vegan\s*(;|$)", cuisine) is not None
        vegan_some = tags.get("diet:vegan") == "yes"
        vegetarian_only = tags.get("diet:vegetarian") == "only" or re.search(r"(^|;)\s*vegetarian\s*(;|$)", cuisine) is not None
        by_city[city].append((tags, vegan_only, vegan_some, vegetarian_only))

    # Two cities can share a name; the country tells them apart in the title of the smaller ones.
    largest = {}
    for index in by_city:
        name = cities[index][2]
        if name not in largest or cities[index][5] > cities[largest[name]][5]:
            largest[name] = index

    def documents():
        for index, places in sorted(by_city.items(), key=lambda item: -cities[item[0]][5]):
            _, _, name, ascii_name, country_code, population = cities[index]
            country = countries.get(country_code, country_code)
            shown = name if largest[name] == index else f"{name} ({country})"
            for diet, plural in (("vegan", "Vegan"), ("vegetarian", "Vegetarian")):
                if diet == "vegan":
                    full = [p[0] for p in places if p[1]]
                    some = [p[0] for p in places if p[2] and not p[1]]
                else:
                    full = [p[0] for p in places if p[3] or p[1]]  # a vegan place is vegetarian too
                    some = []
                if not full and not some:
                    continue
                full.sort(key=lambda t: (-detail(t), t["name"]))
                some.sort(key=lambda t: (-detail(t), t["name"]))
                counts = f"{len(full)} fully {diet} place{'s' if len(full) != 1 else ''} to eat"
                if some:
                    counts += f" and {len(some)} that offer {diet} dishes"
                intro = (f"OpenStreetMap lists {counts} in {name}, {country}, as of {snapshot}. "
                         f"The map data has no ratings, so this list cannot say which is best, and a place may have closed since.")
                sentences = [describe(t, diet, True) for t in full] + [describe(t, diet, False) for t in some]
                passages = []
                for start in range(0, len(sentences), PLACES_PER_PASSAGE):
                    chunk = " ".join(sentences[start:start + PLACES_PER_PASSAGE])
                    passages.append(f"{intro} {chunk}" if start == 0 else chunk)
                names = {name, ascii_name}
                aliases = []
                for city_name in names:
                    aliases += [f"{diet} restaurants in {city_name}", f"{diet} restaurants {city_name}",
                                f"{city_name} {diet} restaurants", f"{diet} food in {city_name}",
                                f"{diet} places in {city_name}", f"{diet} restaurant in {city_name}",
                                f"eat {diet} in {city_name}", f"eat {diet} food in {city_name}",
                                # Spanish and Portuguese, as asked in those languages
                                f"restaurantes {diet}os en {city_name}", f"restaurantes {diet}os em {city_name}"]
                yield {
                    "title": f"{plural} restaurants in {shown}",
                    "url": f"https://www.openstreetmap.org/search?query={urllib.parse.quote(f'{diet} restaurant {name}')}",
                    "aliases": aliases,
                    # Larger cities win when two share a name.
                    "popularity": max(population, 1) / 1e9,
                    "passages": passages,
                }

    meta = {
        "corpus": "places",
        "source": "OpenStreetMap via the Overpass API (eating places tagged vegan or vegetarian), snapshot " + snapshot +
                  "; city assignment from GeoNames cities15000",
        "dump_date": snapshot.replace("-", ""),
        "licence": "ODbL 1.0 (OpenStreetMap data); city names from GeoNames, CC BY 4.0",
        "attribution": "(c) OpenStreetMap contributors; GeoNames",
        # The app uses this pack only where it matches a question well (see Retriever).
        "match": "strict",
    }
    articles, passages = write_index(args.out, documents(), meta)
    print(f"{args.out}: {articles} city lists, {passages} passages, {args.out.stat().st_size / 1e6:.1f} MB")
    print(f"places read: {len(elements)}, without a name, position or city within 50 km: {skipped}, cities: {len(by_city)}")


if __name__ == "__main__":
    main()
