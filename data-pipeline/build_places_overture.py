#!/usr/bin/env python3
"""Builds cityplaces.db: lists of everyday places by city (where to eat and
sleep, pharmacies, hospitals, banks, shops, stations), in the same format as
the Wikipedia index so the app can cite them.

Input: the files written by fetch_overture_places.py (Overture Maps, places
theme, release 2026-09-23.1; CDLA-Permissive-2.0), and GeoNames cities15000
(CC BY 4.0) to give every place a city under one English name.

Usage:
  python data-pipeline/build_places_overture.py [--out data-pipeline/work/index/cityplaces.db]

What the result is and is not:
  - One entry per city and kind of place: "Pharmacies in Nairobi", "Italian
    restaurants in Lyon". A place belongs to the largest city within 15 km, or
    failing that to the nearest city within 50 km.
  - At most 40 places per entry, those Overture is most confident exist, and at
    most two with the same name so that a chain does not fill a list. The entry
    says how many there are in all.
  - Places marked closed are left out. There are no ratings in the data, so
    nothing here says which place is best.
  - It is a snapshot; a place may have closed since.
"""
import argparse
import heapq
import sys
from collections import Counter, defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from build_places import CityFinder, clean, load_cities  # noqa: E402
from docindex import write_index  # noqa: E402

SOURCE = HERE / "work" / "sources" / "overture" / "places"
RELEASE = "2026-09-23.1"
PER_LIST = 40
PER_NAME = 2
PER_PASSAGE = 10
MIN_CUISINE = 3  # a cuisine gets its own list in a city once it has this many places

# list key -> (title, what one place is called, other names people use for the list, give the phone number)
LISTS = {
    "restaurants": ("Restaurants", "restaurant", ["restaurant", "places to eat", "where to eat", "food", "dinner", "lunch"], False),
    "fast_food": ("Fast food", "fast-food place", ["fast food restaurants", "fast food restaurant", "takeaway"], False),
    "cafes": ("Cafés and coffee shops", "café", ["cafes", "cafe", "coffee shops", "coffee shop", "coffee"], False),
    "bars": ("Bars and pubs", "bar", ["bars", "bar", "pubs", "pub", "nightlife"], False),
    "bakeries": ("Bakeries", "bakery", ["bakery", "bread"], False),
    "ice_cream": ("Ice cream shops", "ice-cream shop", ["ice cream", "ice cream shop", "gelato"], False),
    "pharmacies": ("Pharmacies", "pharmacy", ["pharmacy", "chemist", "chemists", "drugstore", "drugstores", "drug store"], True),
    "hospitals": ("Hospitals", "hospital", ["hospital", "emergency room", "emergency rooms", "emergency department"], True),
    "clinics": ("Medical clinics", "clinic", ["clinics", "clinic", "doctors", "doctor", "urgent care", "medical clinic"], True),
    "dentists": ("Dentists", "dental clinic", ["dentist", "dental clinics", "dental clinic"], True),
    "atms": ("ATMs", "cash machine", ["atm", "cash machines", "cash machine", "cashpoint", "cash points"], False),
    "banks": ("Banks", "bank", ["bank"], False),
    "exchange": ("Currency exchange offices", "currency exchange office", ["currency exchange", "money exchange", "bureau de change", "money changers"], False),
    "hotels": ("Hotels", "hotel", ["hotel", "where to stay", "accommodation", "places to stay", "lodging"], True),
    "hostels": ("Hostels", "hostel", ["hostel", "backpacker hostels", "cheap accommodation"], True),
    "bnb": ("Bed and breakfasts", "bed and breakfast", ["bed and breakfast", "guesthouses", "guest houses", "guesthouse"], True),
    "camping": ("Campgrounds", "campground", ["campground", "campsites", "campsite", "camping"], True),
    "groceries": ("Supermarkets and grocery stores", "grocery store", ["supermarkets", "supermarket", "grocery stores", "grocery store", "groceries"], False),
    "convenience": ("Convenience stores", "convenience store", ["convenience store", "corner shops", "minimarkets"], False),
    "malls": ("Shopping malls and department stores", "shopping centre", ["shopping malls", "shopping mall", "malls", "department stores", "shopping centres", "shopping"], False),
    "markets": ("Markets", "market", ["market", "farmers markets", "farmers market", "street markets"], False),
    "bookshops": ("Bookshops", "bookshop", ["bookshop", "bookstores", "bookstore", "book shops"], False),
    "train": ("Train stations", "train station", ["train station", "railway stations", "railway station"], False),
    "bus": ("Bus stations", "bus station", ["bus station", "bus terminals", "bus terminal", "coach stations"], False),
    "metro": ("Metro stations", "metro station", ["metro station", "subway stations", "subway station", "underground stations"], False),
    "airports": ("Airports", "airport", ["airport"], False),
    "ferries": ("Ferry services", "ferry service", ["ferry", "ferries", "ferry terminal", "ferry terminals"], False),
    "fuel": ("Petrol stations", "petrol station", ["petrol station", "gas stations", "gas station", "fuel stations", "filling stations"], False),
    "ev": ("Electric vehicle charging stations", "charging station", ["ev charging", "ev charging stations", "charging stations", "car chargers"], False),
    "police": ("Police stations", "police station", ["police station", "police"], True),
    "embassies": ("Embassies and consulates", "embassy or consulate", ["embassies", "embassy", "consulates", "consulate"], True),
    "museums": ("Museums", "museum", ["museum"], False),
    "libraries": ("Libraries", "library", ["library", "public libraries"], False),
    "toilets": ("Public toilets", "public toilet", ["public toilet", "public restrooms", "restrooms", "toilets"], False),
    "laundry": ("Laundromats", "laundromat", ["laundromat", "laundrettes", "launderette", "laundry", "self-service laundry"], False),
    "post": ("Post offices", "post office", ["post office"], False),
}
# Overture's group or kind -> list key. A kind wins over its group.
KIND_LIST = {
    "bakery": "bakeries", "ice_cream_shop": "ice_cream", "diner": "restaurants", "sandwich_shop": "fast_food",
    "tapas_bar": "bars", "gastropub": "bars", "bistro": "restaurants", "delicatessen": "groceries",
    "grocery_store": "groceries", "organic_grocery_store": "groceries", "health_food_store": "groceries",
    "hostel": "hostels", "lodging": "hotels", "lodge": "hotels",
    "bus_station": "bus", "metro_station": "metro", "light_rail_and_subway_station": "metro", "ferry_service": "ferries",
    "currency_exchange": "exchange", "laundromat": "laundry", "post_office": "post", "bookstore": "bookshops",
    "urgent_care_clinic": "clinics", "walk_in_clinic": "clinics",
}
GROUP_LIST = {
    "restaurant": "restaurants", "fast_food_restaurant": "fast_food", "cafe": "cafes", "coffee_shop": "cafes", "bar": "bars",
    "pharmacy_and_drug_store": "pharmacies", "hospital": "hospitals", "emergency_or_urgent_care_facility": "hospitals",
    "emergency_department": "hospitals", "primary_care_or_general_clinic": "clinics", "dental_clinic": "dentists",
    "atm": "atms", "bank_or_credit_union": "banks", "hotel": "hotels", "bed_and_breakfast": "bnb", "resort": "hotels",
    "inn": "hotels", "campground": "camping", "convenience_store": "convenience", "shopping_mall": "malls",
    "department_store": "malls", "market": "markets", "farmers_market": "markets", "train_station": "train",
    "airport": "airports", "gas_station": "fuel", "ev_charging_station": "ev", "police_station": "police",
    "embassy": "embassies", "museum": "museums", "library": "libraries", "public_restroom": "toilets",
}


def cuisine_of(kind):
    """'italian_restaurant' -> 'Italian'; None for the plain kind and for kinds that are not a cuisine."""
    if not kind or not kind.endswith("_restaurant") or kind in ("fast_food_restaurant", "theme_restaurant", "buffet_restaurant"):
        return None
    return kind[: -len("_restaurant")].replace("_", " ")


def describe(name, noun, detail, street, postcode, phone):
    """One sentence about a place, with no full stop inside it that could pass for a sentence end."""
    parts = [f"{clean(name).replace('. ', ' ')} is a {noun}" + (f" ({detail})" if detail else "")]
    address = ", ".join(clean(v).replace(". ", " ") for v in (street, postcode) if v and v.strip())
    if address:
        parts.append("address: " + address)
    if phone:
        parts.append("phone " + phone)
    return "; ".join(parts) + "."


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--out", type=Path, default=HERE / "work" / "index" / "cityplaces.db")
    args = parser.parse_args()
    try:
        import duckdb
    except ImportError:
        sys.exit("duckdb is not installed. Run: pip install duckdb")
    files = sorted(SOURCE.glob("*.parquet"))
    if not files:
        sys.exit(f"no input in {SOURCE}; run fetch_overture_places.py first")

    cities, countries = load_cities()
    finder = CityFinder(cities)
    # (city, list key) -> the best PER_LIST places as a heap of (confidence, tie-break, row), and how many there were.
    best = defaultdict(list)
    totals = Counter()
    cell_city = {}  # places 1 km apart share a city; this saves most lookups
    con = duckdb.connect()
    cursor = con.execute(f"SELECT name, grp, kind, confidence, street, postcode, lon, lat, phone FROM '{SOURCE.as_posix()}/*.parquet'")
    read = unplaced = serial = 0
    while True:
        rows = cursor.fetchmany(200_000)
        if not rows:
            break
        for name, grp, kind, confidence, street, postcode, lon, lat, phone in rows:
            read += 1
            key = KIND_LIST.get(kind) or GROUP_LIST.get(grp)
            if key is None or lat is None:
                continue
            cell = (round(lat, 2), round(lon, 2))
            city = cell_city.get(cell, -1)
            if city == -1:
                city = cell_city[cell] = finder.find(lat, lon)
            if city is None:
                unplaced += 1
                continue
            cuisine = cuisine_of(kind) if key == "restaurants" else None
            serial += 1
            item = (confidence, bool(street) + bool(phone), serial, (name, kind, cuisine, street, postcode, phone))
            for target in ([key, "cuisine:" + cuisine] if cuisine else [key]):
                totals[(city, target)] += 1
                heap = best[(city, target)]
                # Keep a few more than needed: same-name places are thinned out afterwards.
                if len(heap) < PER_LIST * 3:
                    heapq.heappush(heap, item)
                elif item > heap[0]:
                    heapq.heapreplace(heap, item)
        print(f"  read {read:,} places", flush=True)

    largest = {}
    for city, _ in best:
        name = cities[city][2]
        if name not in largest or cities[city][5] > cities[largest[name]][5]:
            largest[name] = city

    def documents():
        for (city, target), heap in sorted(best.items(), key=lambda item: (-cities[item[0][0]][5], item[0][1])):
            _, _, name, ascii_name, country_code, population = cities[city]
            country = countries.get(country_code, country_code)
            shown = name if largest[name] == city else f"{name} ({country})"
            if target.startswith("cuisine:"):
                cuisine = target[len("cuisine:"):]
                if totals[(city, target)] < MIN_CUISINE:
                    continue
                label = cuisine.capitalize() if cuisine.islower() else cuisine
                title, noun, others, with_phone = f"{label} restaurants", f"{cuisine} restaurant", [f"{cuisine} restaurant", f"{cuisine} food"], False
            else:
                title, noun, others, with_phone = LISTS[target]
            seen = Counter()
            chosen = []
            for _, _, _, row in sorted(heap, reverse=True):
                if seen[row[0].lower()] >= PER_NAME:
                    continue
                seen[row[0].lower()] += 1
                chosen.append(row)
                if len(chosen) == PER_LIST:
                    break
            total = totals[(city, target)]
            sentences = []
            for place_name, kind, cuisine, street, postcode, phone in chosen:
                detail = cuisine if (target == "restaurants" and cuisine) else None
                sentences.append(describe(place_name, noun, detail, street, postcode, phone if with_phone else None))
            intro = (f"Overture Maps lists {total:,} {title[0].lower() + title[1:]} in {name}, {country} (release {RELEASE}); "
                     f"{'all' if len(chosen) == total else 'the ' + str(len(chosen)) + ' it is most sure of'} are given here. "
                     f"The list is not ranked by quality, because the data has no ratings, and a place may have closed since.")
            passages = []
            for start in range(0, len(sentences), PER_PASSAGE):
                chunk = " ".join(sentences[start:start + PER_PASSAGE])
                passages.append(f"{intro} {chunk}" if start == 0 else chunk)
            aliases = []
            for city_name in {name, ascii_name}:
                for word in [title.lower()] + others:
                    aliases += [f"{word} in {city_name}", f"{word} {city_name}", f"{city_name} {word}", f"{word} near {city_name}"]
            yield {
                "title": f"{title} in {shown}",
                "url": f"https://www.openstreetmap.org/search?query={title.replace(' ', '+')}+{name.replace(' ', '+')}",
                "aliases": aliases,
                # Larger cities win when two share a name; a general list wins over a cuisine's.
                "popularity": max(population, 1) / 1e9 * (0.5 if target.startswith("cuisine:") else 1.0),
                "passages": passages,
            }

    meta = {
        "corpus": "cityplaces",
        "source": f"Overture Maps Foundation, places theme, release {RELEASE} (overturemaps.org); "
                  "city assignment from GeoNames cities15000",
        "dump_date": RELEASE.split(".")[0].replace("-", ""),
        "licence": "CDLA-Permissive-2.0 (Overture Maps places); city names from GeoNames, CC BY 4.0",
        "attribution": "Overture Maps Foundation and its data contributors (Meta, Microsoft, Foursquare and others); GeoNames",
        # The app uses this pack only where it matches a question well (see Retriever).
        "match": "strict",
    }
    articles, passages = write_index(args.out, documents(), meta)
    print(f"{args.out}: {articles:,} lists, {passages:,} passages, {args.out.stat().st_size / 1e6:.0f} MB")
    print(f"places read: {read:,}; with no city within 50 km: {unplaced:,}; cities: {len({c for c, _ in best}):,}")


if __name__ == "__main__":
    main()
