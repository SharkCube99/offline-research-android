#!/usr/bin/env python3
"""Builds firstaid.db: the Wikibooks "First Aid" book, one entry per chapter, in
the same format as the Wikipedia index so the app can cite it.

Wikipedia describes injuries; a first-aid book says what to do about them, in
the order to do it.

Source (fetched into data-pipeline/work/sources/firstaid/ if missing):
  https://en.wikibooks.org/wiki/First_Aid, through the MediaWiki API.
  CC BY-SA 4.0, Wikibooks contributors.

Usage:
  python data-pipeline/build_firstaid.py [--out data-pipeline/work/index/firstaid.db]

The book is written by volunteers and is not a substitute for training or for
professional care; every entry opens by saying so.
"""
import argparse
import datetime
import json
import re
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from docindex import write_index  # noqa: E402
from textproc import chunk_text, clean_text, count_words  # noqa: E402

SOURCES = HERE / "work" / "sources" / "firstaid"
API = "https://en.wikibooks.org/w/api.php"
USER_AGENT = "offline-research-index-builder/0.1 (https://github.com/SharkCube99/offline-research-android)"
BOOK = "First Aid"
# Pages about the book itself, not about first aid.
SKIP = ("Authors", "Appendix B", "Appendix C", "Appendix D", "Manual of Style", "List of Images", "Meta content",
        "History", "How To Read This Book", "First Aid Training", "Appendices", "Introduction", "Print version", "Templates")
# Other words people use for a chapter's subject, so a question in everyday words finds it.
ALSO = {
    "Burns": ["scald", "scalds", "burn", "sunburn"],
    "Fractures": ["broken bone", "broken bones", "broken leg", "broken arm", "broken ankle"],
    "Bone & Joint Injuries": ["sprain", "sprains", "sprained ankle", "dislocation", "twisted ankle"],
    "Musculoskeletal Injuries": ["sprain", "strain", "muscle injury"],
    "Anaphylactic Shock": ["anaphylaxis", "allergic reaction", "severe allergic reaction", "bee sting allergy"],
    "Obstructed Airway": ["choking", "heimlich manoeuvre", "heimlich maneuver"],
    "External Bleeding": ["bleeding", "deep cut", "wound", "cuts", "severe bleeding"],
    "D for Deadly Bleeding": ["severe bleeding", "tourniquet"],
    "Heat-Related Illness & Injury": ["heat stroke", "heatstroke", "heat exhaustion", "sunstroke"],
    "Cold-Related Illness & Injury": ["hypothermia", "frostbite"],
    "Pressure-Related Illness & Injury": ["altitude sickness", "decompression sickness", "the bends"],
    "Marine First Aid": ["jellyfish sting", "drowning", "near drowning"],
    "Poisoning": ["poison", "food poisoning", "overdose"],
    "Heart Attack & Angina": ["heart attack", "chest pain"],
    "Circulatory Emergencies": ["stroke", "shock", "fainting"],
    "Respiratory Emergencies": ["difficulty breathing", "asthma attack"],
    "Asthma & Hyperventilation": ["asthma attack", "hyperventilation", "panic attack"],
    "Electrocution": ["electric shock", "lightning strike"],
    "Head & Facial Injuries": ["concussion", "head injury", "nosebleed", "knocked out tooth"],
    "Epilepsy": ["seizure", "seizures", "convulsions"],
    "Diabetes": ["low blood sugar", "hypoglycaemia", "hypoglycemia"],
    "C for Compressions": ["cpr", "chest compressions"],
    "CPR summary": ["cpr", "cardiopulmonary resuscitation"],
    "Appendix E: First Aid Kits": ["first aid kit", "first aid kits", "what to pack in a first aid kit"],
}


def api(params, cache):
    dest = SOURCES / cache
    if not dest.exists():
        dest.parent.mkdir(parents=True, exist_ok=True)
        request = urllib.request.Request(API + "?" + urllib.parse.urlencode(dict(params, format="json")),
                                         headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(request, timeout=120) as response:
            dest.write_bytes(response.read())
        time.sleep(0.5)  # be a light user of a shared service
    return json.loads(dest.read_text(encoding="utf-8"))


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--out", type=Path, default=HERE / "work" / "index" / "firstaid.db")
    args = parser.parse_args()

    listing = api({"action": "query", "list": "allpages", "apprefix": BOOK.replace(" ", "_") + "/", "aplimit": 500}, "pages.json")
    titles = [p["title"] for p in listing["query"]["allpages"]]
    snapshot = datetime.date.fromtimestamp((SOURCES / "pages.json").stat().st_mtime).isoformat()

    def documents():
        for title in titles:
            chapter = title.split("/", 1)[1]
            if chapter.startswith(SKIP) or "/" in chapter:
                continue
            page = api({"action": "query", "prop": "extracts", "explaintext": 1, "exsectionformat": "plain", "titles": title},
                       re.sub(r"[^A-Za-z0-9]+", "_", chapter) + ".json")
            text = next(iter(page["query"]["pages"].values())).get("extract", "")
            text = clean_text(text)
            if count_words(text) < 60:
                continue
            intro = (f"From the Wikibooks book First Aid, chapter \"{chapter}\", as of {snapshot}. "
                     f"Written by volunteers; it does not replace training or professional medical care. ")
            passages = chunk_text(text)
            passages[0] = intro + passages[0]
            plain = chapter.replace(" & ", " and ")
            aliases = [chapter, plain, f"{plain} first aid", f"first aid for {plain}", f"{plain} treatment"]
            for word in ALSO.get(chapter, []):
                aliases += [word, f"{word} first aid", f"first aid for {word}", f"{word} treatment", f"treating {word}",
                            f"how to treat {word}"]
            yield {
                "title": f"First aid: {chapter}",
                "url": "https://en.wikibooks.org/wiki/" + urllib.parse.quote(title.replace(" ", "_")),
                "aliases": aliases,
                "passages": passages,
            }

    meta = {
        "corpus": "firstaid",
        "source": "Wikibooks, First Aid (https://en.wikibooks.org/wiki/First_Aid), snapshot " + snapshot,
        "dump_date": snapshot.replace("-", ""),
        "licence": "CC BY-SA 4.0",
        "attribution": "Wikibooks contributors",
        # The app uses this pack only where it matches a question well (see Retriever).
        "match": "strict",
    }
    articles, passages = write_index(args.out, documents(), meta)
    print(f"{args.out}: {articles} chapters, {passages} passages, {args.out.stat().st_size / 1e6:.2f} MB")


if __name__ == "__main__":
    main()
