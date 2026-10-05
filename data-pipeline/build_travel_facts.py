#!/usr/bin/env python3
"""Builds travelfacts.db: one short entry of practical facts per country, in the
same format as the Wikipedia index so the app can cite it.

A traveller's questions about a country are often a lookup: the emergency
numbers, the plug and voltage, the side of the road, the currency, the dialling
code. In Wikipedia those sit in long tables that search does not reach.

Source (fetched into data-pipeline/work/sources/travelfacts/ if missing):
  Wikidata, through its public query service. CC0 1.0.

Usage:
  python data-pipeline/build_travel_facts.py [--out data-pipeline/work/index/travelfacts.db]

Each entry says where it comes from and when. Wikidata is edited by volunteers:
a fact can be missing or out of date, and an entry only states what it has.
"""
import argparse
import datetime
import json
import sys
import urllib.parse
import urllib.request
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from docindex import write_index  # noqa: E402

SOURCES = HERE / "work" / "sources" / "travelfacts"
ENDPOINT = "https://query.wikidata.org/sparql"
USER_AGENT = "offline-research-index-builder/0.1 (https://github.com/SharkCube99/offline-research-android)"

# One query per fact, so a country missing one fact still has the others and no
# query grows into a product of several multi-valued properties.
COUNTRIES = """
SELECT ?c ?cLabel ?population WHERE {
  ?c wdt:P31 wd:Q3624078 .
  FILTER NOT EXISTS { ?c wdt:P576 ?dissolved }
  OPTIONAL { ?c wdt:P1082 ?population }
  SERVICE wikibase:label { bd:serviceParam wikibase:language "en". }
}"""
FACTS = {
    "capital": "SELECT ?c ?vLabel WHERE { ?c wdt:P31 wd:Q3624078 ; wdt:P36 ?v . %s }",
    "currency": "SELECT ?c ?vLabel ?code WHERE { ?c wdt:P31 wd:Q3624078 ; wdt:P38 ?v . OPTIONAL { ?v wdt:P498 ?code } %s }",
    "language": "SELECT ?c ?vLabel WHERE { ?c wdt:P31 wd:Q3624078 ; wdt:P37 ?v . %s }",
    "driving": "SELECT ?c ?vLabel WHERE { ?c wdt:P31 wd:Q3624078 ; wdt:P1622 ?v . %s }",
    "plug": "SELECT ?c ?vLabel WHERE { ?c wdt:P31 wd:Q3624078 ; wdt:P2853 ?v . %s }",
    "voltage": "SELECT ?c ?v WHERE { ?c wdt:P31 wd:Q3624078 ; wdt:P2884 ?v . }",
    "calling": "SELECT ?c ?v WHERE { ?c wdt:P31 wd:Q3624078 ; wdt:P474 ?v . }",
    "emergency": "SELECT ?c ?vLabel ?useLabel WHERE { ?c wdt:P31 wd:Q3624078 ; p:P2852 ?s . ?s ps:P2852 ?v . "
                 "OPTIONAL { ?s pq:P366 ?use } %s }",
    "timezone": "SELECT ?c ?vLabel WHERE { ?c wdt:P31 wd:Q3624078 ; wdt:P421 ?v . %s }",
}
LABELS = 'SERVICE wikibase:label { bd:serviceParam wikibase:language "en". }'


def query(name, sparql):
    """Rows of a query as lists of {variable: value}; cached on disk."""
    dest = SOURCES / f"{name}.json"
    if not dest.exists():
        dest.parent.mkdir(parents=True, exist_ok=True)
        print(f"asking Wikidata for {name}", flush=True)
        request = urllib.request.Request(ENDPOINT + "?" + urllib.parse.urlencode({"query": sparql, "format": "json"}),
                                         headers={"User-Agent": USER_AGENT, "Accept": "application/sparql-results+json"})
        with urllib.request.urlopen(request, timeout=180) as response:
            dest.write_bytes(response.read())
    data = json.loads(dest.read_text(encoding="utf-8"))
    return [{key: cell["value"] for key, cell in row.items()} for row in data["results"]["bindings"]]


# Travellers and adapter packaging use the letter names; Wikidata uses the standards' names.
PLUG_LETTER = {
    "NEMA 1-15": "Type A", "NEMA 5-15": "Type B", "Europlug": "Type C (Europlug)", "BS 546": "Type D or M (BS 546)",
    "CEE 7/5": "Type E", "CEE 7/6": "Type E", "Schuko": "Type F (Schuko)", "CEE 7/4": "Type F (Schuko)",
    "BS 1363": "Type G (BS 1363)", "SI 32": "Type H", "AS/NZS 3112": "Type I", "SEV 1011": "Type J",
    "Section 107-2-D1": "Type K", "CEI 23-50": "Type L", "IEC 60906-1": "Type N (IEC 60906-1)", "TIS 166-2549": "Type O",
}


def is_label(text):
    """False for a missing English label, which the query service returns as the bare item id."""
    return bool(text) and not (text.startswith("Q") and text[1:].isdigit()) and not text.startswith("http")


def join(values, limit=8):
    values = sorted(set(values))
    return ", ".join(values[:limit]) + (" and others" if len(values) > limit else "")


def number(text):
    value = float(text)
    return str(int(value)) if value == int(value) else str(value)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--out", type=Path, default=HERE / "work" / "index" / "travelfacts.db")
    args = parser.parse_args()

    countries = {}
    for row in query("countries", COUNTRIES):
        if is_label(row.get("cLabel", "")):
            entry = countries.setdefault(row["c"], {"name": row["cLabel"], "population": 0})
            entry["population"] = max(entry["population"], int(float(row.get("population", 0) or 0)))
    facts = {name: defaultdict(list) for name in FACTS}
    for name, sparql in FACTS.items():
        for row in query(name, sparql % LABELS if "%s" in sparql else sparql):
            facts[name][row["c"]].append(row)
    snapshot = datetime.date.fromtimestamp((SOURCES / "countries.json").stat().st_mtime).isoformat()

    def labels(name, country):
        return [r["vLabel"] for r in facts[name].get(country, []) if is_label(r.get("vLabel", ""))]

    def documents():
        for country, entry in sorted(countries.items(), key=lambda item: -item[1]["population"]):
            name = entry["name"]
            sentences = []
            uses = defaultdict(set)  # number -> what it is for
            for row in facts["emergency"].get(country, []):
                if is_label(row.get("vLabel", "")):
                    uses[row["vLabel"]].update([row["useLabel"]] if is_label(row.get("useLabel", "")) else [])
            if uses:
                emergency = [f"{number_} for {', '.join(sorted(what))}" if what else number_ for number_, what in sorted(uses.items())]
                sentences.append(f"Emergency telephone numbers in {name}: " + "; ".join(emergency[:8]) + ".")
            plugs = [PLUG_LETTER.get(p, p) for p in labels("plug", country)]
            volts = [number(r["v"]) for r in facts["voltage"].get(country, [])]
            if plugs or volts:
                parts = []
                if volts:
                    parts.append(f"mains voltage {join(volts)} V")
                if plugs:
                    parts.append(f"plug types: {join(plugs)}")
                sentences.append(f"Electricity in {name}: " + "; ".join(parts) + ".")
            driving = labels("driving", country)
            if driving:
                sentences.append(f"Traffic in {name} drives on the {join(driving)} side of the road.")
            currency = []
            for row in facts["currency"].get(country, []):
                if is_label(row.get("vLabel", "")):
                    currency.append(f"{row['vLabel']} ({row['code']})" if row.get("code") else row["vLabel"])
            if currency:
                sentences.append(f"Currency of {name}: {join(currency)}.")
            calling = [r["v"] for r in facts["calling"].get(country, [])]
            if calling:
                sentences.append(f"International dialling code of {name}: {join(calling)}.")
            capital = labels("capital", country)
            if capital:
                sentences.append(f"Capital of {name}: {join(capital)}.")
            languages = labels("language", country)
            if languages:
                sentences.append(f"Official language{'s' if len(languages) > 1 else ''} of {name}: {join(languages)}.")
            zones = labels("timezone", country)
            if zones:
                sentences.append(f"Time zone{'s' if len(zones) > 1 else ''} in {name}: {join(zones, 6)}.")
            if not sentences:
                continue
            intro = (f"Practical facts about {name} for travellers, from Wikidata as of {snapshot}. "
                     f"Wikidata is edited by volunteers, so a fact may be out of date; check anything that matters.")
            aliases = []
            for topic in ("emergency numbers", "emergency number", "emergency telephone numbers", "plug type", "plug types",
                          "plugs and voltage", "voltage", "electricity", "power sockets", "driving side", "currency",
                          "dialling code", "calling code", "country code", "time zone", "official language", "travel facts"):
                aliases += [f"{topic} in {name}", f"{topic} {name}", f"{name} {topic}", f"{topic} of {name}"]
            yield {
                "title": f"{name}: travel facts",
                "url": country.replace("http://www.wikidata.org/entity/", "https://www.wikidata.org/wiki/"),
                "aliases": aliases,
                "popularity": max(entry["population"], 1) / 1e10,
                "passages": [intro + " " + " ".join(sentences)],
            }

    meta = {
        "corpus": "travelfacts",
        "source": "Wikidata query service (sovereign states: emergency numbers, plugs, voltage, driving side, currency, "
                  "dialling code, capital, languages, time zones), snapshot " + snapshot,
        "dump_date": snapshot.replace("-", ""),
        "licence": "CC0 1.0",
        "attribution": "Wikidata contributors",
        # The app uses this pack only where it matches a question well (see Retriever).
        "match": "strict",
    }
    articles, passages = write_index(args.out, documents(), meta)
    print(f"{args.out}: {articles} countries, {passages} passages, {args.out.stat().st_size / 1e6:.2f} MB")
    print({name: len(rows) for name, rows in facts.items()})


if __name__ == "__main__":
    main()
