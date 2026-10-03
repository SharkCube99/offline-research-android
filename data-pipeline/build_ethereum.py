#!/usr/bin/env python3
"""Builds ethereum.db: an index of Ethereum and post-quantum cryptography texts
in the same format as the Wikipedia index, so the app can cite them.

Wikipedia says little about individual Ethereum proposals or the NIST
post-quantum standards. The primary texts are short, freely licensed and say
exactly what a mechanism does.

Sources (fetched into data-pipeline/work/sources/ if missing):
  github.com/ethereum/EIPs             proposals (EIPs)                 CC0-1.0
  github.com/ethereum/ERCs             application standards (ERCs)     CC0-1.0
  github.com/ethereum/consensus-specs  proof-of-stake specifications    CC0-1.0
  github.com/ethereum/ethereum-org-website  English pages of ethereum.org  MIT
  NIST FIPS 203, 204, 205 and SP 800-208 (PDF)                          US Government works, public domain

Usage:
  python data-pipeline/build_ethereum.py [--out data-pipeline/work/index/ethereum.db]

The NIST PDFs need the pypdf package (pip install pypdf); without it they are
left out and the script says so.
"""
import argparse
import datetime
import re
import subprocess
import sys
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from docindex import write_index  # noqa: E402
from textproc import chunk_text, clean_text, count_words  # noqa: E402

SOURCES = HERE / "work" / "sources"
REPOS = {
    "EIPs": "https://github.com/ethereum/EIPs.git",
    "ERCs": "https://github.com/ethereum/ERCs.git",
    "consensus-specs": "https://github.com/ethereum/consensus-specs.git",
}
NIST = [
    ("FIPS 203: Module-Lattice-Based Key-Encapsulation Mechanism Standard (ML-KEM)",
     ["FIPS 203", "ML-KEM", "CRYSTALS-Kyber", "Kyber"],
     "https://nvlpubs.nist.gov/nistpubs/FIPS/NIST.FIPS.203.pdf"),
    ("FIPS 204: Module-Lattice-Based Digital Signature Standard (ML-DSA)",
     ["FIPS 204", "ML-DSA", "CRYSTALS-Dilithium", "Dilithium"],
     "https://nvlpubs.nist.gov/nistpubs/FIPS/NIST.FIPS.204.pdf"),
    ("FIPS 205: Stateless Hash-Based Digital Signature Standard (SLH-DSA)",
     ["FIPS 205", "SLH-DSA", "SPHINCS+", "SPHINCS"],
     "https://nvlpubs.nist.gov/nistpubs/FIPS/NIST.FIPS.205.pdf"),
    ("NIST SP 800-208: Recommendation for Stateful Hash-Based Signature Schemes (LMS and XMSS)",
     ["SP 800-208", "LMS", "XMSS", "Leighton-Micali Signature", "stateful hash-based signatures"],
     "https://nvlpubs.nist.gov/nistpubs/SpecialPublications/NIST.SP.800-208.pdf"),
]

FRONT_MATTER = re.compile(r"\A---\s*\n(.*?)\n---\s*\n", re.S)
CODE_BLOCK = re.compile(r"^(```|~~~).*?^\1[ \t]*$", re.S | re.M)
HTML_COMMENT = re.compile(r"<!--.*?-->", re.S)
HTML_TAG = re.compile(r"</?[A-Za-z][^>\n]*>")
IMAGE = re.compile(r"!\[[^\]]*\]\([^)]*\)")
LINK = re.compile(r"\[([^\]]+)\]\([^)]*\)")
ANCHOR = re.compile(r"[ \t]*\{#[^}]*\}")  # ethereum.org's heading anchors, "{#how-it-works}"
HEADING = re.compile(r"^#{1,6}\s*(.+?)\s*#*\s*$", re.M)
TABLE_RULE = re.compile(r"^\s*\|?[\s:|-]+\|[\s:|-]*$", re.M)
EIP_LINK_TEXT = re.compile(r"\b(EIP|ERC)[- ]?(\d{1,5})\b")


def front_matter(text):
    """(fields, body). Fields are the 'key: value' lines between the opening '---' pair."""
    match = FRONT_MATTER.match(text)
    if not match:
        return {}, text
    fields = {}
    for line in match.group(1).splitlines():
        key, sep, value = line.partition(":")
        if sep and key.strip() and not key.startswith(" "):
            fields[key.strip().lower()] = value.strip().strip("\"'")
    return fields, text[match.end():]


def plain(markdown):
    """Markdown to plain prose. Code blocks go: they are reference implementations
    and test vectors, not statements a reader would quote."""
    text = CODE_BLOCK.sub(" ", markdown)
    text = ANCHOR.sub("", text)
    text = HTML_COMMENT.sub(" ", text)
    text = IMAGE.sub(" ", text)
    text = LINK.sub(r"\1", text)
    text = TABLE_RULE.sub(" ", text)
    # A heading becomes a short sentence of its own, so it stays with what follows it.
    text = HEADING.sub(lambda m: m.group(1).rstrip(".:") + ".", text)
    text = HTML_TAG.sub(" ", text)
    # A single "|" separates table cells; "||" is the concatenation sign of the specifications.
    text = re.sub(r"(?<!\|)\|(?!\|)", " ; ", text)
    text = text.replace("`", "").replace("**", "").replace("__", "")
    text = re.sub(r"^\s*[-*+]\s+", "", text, flags=re.M)
    return clean_text(text)


def ensure_sources():
    SOURCES.mkdir(parents=True, exist_ok=True)
    for name, url in REPOS.items():
        if not (SOURCES / name).exists():
            print(f"fetching {url}", flush=True)
            subprocess.run(["git", "clone", "-q", "--depth", "1", url, str(SOURCES / name)], check=True)
    site = SOURCES / "ethereum-org-website"
    if not site.exists():
        print("fetching ethereum.org content", flush=True)
        subprocess.run(["git", "clone", "-q", "--depth", "1", "--filter=blob:none", "--sparse",
                        "https://github.com/ethereum/ethereum-org-website.git", str(site)], check=True)
        subprocess.run(["git", "-C", str(site), "sparse-checkout", "set", "--no-cone",
                        "/public/content/*", "!/public/content/translations/"], check=True)


def revision(name):
    return subprocess.run(["git", "-C", str(SOURCES / name), "rev-parse", "--short", "HEAD"],
                          capture_output=True, text=True).stdout.strip()


def upgrades(eip_dir):
    """{proposal number: network upgrade} read from the 'Hardfork Meta' proposals,
    which list what each upgrade included."""
    found = {}
    for path in eip_dir.glob("eip-*.md"):
        fields, body = front_matter(path.read_text(encoding="utf-8"))
        match = re.match(r"Hardfork Meta\s*[-:]\s*(.+)", fields.get("title", ""))
        # Only upgrades that are final: a draft list can still change.
        if not match or fields.get("status") != "Final":
            continue
        upgrade = match.group(1).strip()
        # Only the list of what went in: from the "Included" heading to the first
        # heading that starts something else ("considered" and "declined" lists,
        # the activation table, the rationale).
        section = re.search(
            r"^#+\s*Included[^\n]*\n(.*?)(?=^#+\s*(?:Full Specifications|Activation|Considered|Declined|Proposed|"
            r"Rationale|Security|Copyright)|\Z)", body, re.S | re.M)
        if not section:
            continue
        for number in re.findall(r"eip-(\d+)\.md", section.group(1)):
            found.setdefault(int(number), upgrade)
    return found


def proposals(directory, kind, base_url, upgrade_of):
    for path in sorted(directory.glob(f"{kind.lower()}-*.md"), key=lambda p: int(re.findall(r"\d+", p.stem)[0])):
        fields, body = front_matter(path.read_text(encoding="utf-8"))
        number = fields.get("eip") or re.findall(r"\d+", path.stem)[0]
        status = fields.get("status", "")
        if not fields.get("title") or status in ("Withdrawn", "Moved"):
            continue
        name = f"{kind}-{number}"
        facts = [f"{name}, \"{fields['title']}\", is an Ethereum {'application standard (ERC)' if kind == 'ERC' else 'Improvement Proposal'}."]
        if fields.get("description"):
            facts.append(f"Summary: {fields['description'].rstrip('.')}.")
        category = ", ".join(v for v in (fields.get("type"), fields.get("category")) if v)
        facts.append(f"Status: {status}" + (f" ({category})." if category else "."))
        if int(number) in upgrade_of:
            facts.append(f"Network upgrade: it is part of the {upgrade_of[int(number)]} upgrade.")
        if fields.get("created"):
            facts.append(f"Created: {fields['created']}.")
        if fields.get("requires"):
            facts.append("Requires: " + ", ".join(f"EIP-{n.strip()}" for n in fields["requires"].split(",")) + ".")
        text = " ".join(facts) + " " + plain(body)
        other = "EIP" if kind == "ERC" else "ERC"
        yield {
            "title": f"{name}: {fields['title']}",
            "url": f"{base_url}{kind.lower()}-{number}",
            "aliases": [name, f"{kind} {number}", f"{kind}{number}", f"{other}-{number}", fields["title"]],
            "popularity": 2e-6 if status == "Final" else 1e-6,
            "passages": chunk_text(text),
        }


def consensus_specs(directory):
    for path in sorted(directory.rglob("*.md")):
        relative = path.relative_to(directory).with_suffix("")
        parts = list(relative.parts)
        if parts[0].startswith("_") or "p2p" in parts[-1]:
            continue
        text = plain(path.read_text(encoding="utf-8"))
        if count_words(text) < 80:
            continue
        fork, topic = parts[0], " ".join(parts[1:]).replace("-", " ").replace("_", " ")
        yield {
            "title": f"Ethereum consensus specification, {fork.capitalize()}: {topic}",
            "url": f"https://github.com/ethereum/consensus-specs/blob/master/specs/{relative.as_posix()}.md",
            "aliases": [f"{fork} {topic}", f"{topic} specification"],
            "passages": chunk_text(text),
        }


def ethereum_org(directory):
    for path in sorted(directory.rglob("index.md")):
        relative = path.parent.relative_to(directory)
        parts = relative.parts
        # English pages only; tutorials are long code walkthroughs, and the community
        # and contributing pages are about the website, not about Ethereum.
        if not parts or parts[0] in ("translations", "community", "contributing", "about") or "tutorials" in parts:
            continue
        fields, body = front_matter(path.read_text(encoding="utf-8"))
        if fields.get("lang", "en") != "en" or not fields.get("title"):
            continue
        text = plain(body)
        if count_words(text) < 80:
            continue
        if fields.get("description"):
            text = fields["description"].rstrip(".") + ". " + text
        yield {
            "title": f"ethereum.org: {fields['title']}",
            "url": "https://ethereum.org/en/" + relative.as_posix() + "/",
            "aliases": [fields["title"], parts[-1].replace("-", " ")],
            "popularity": 1.5e-6,
            "passages": chunk_text(text),
        }


def nist():
    try:
        from pypdf import PdfReader
    except ImportError:
        print("pypdf is not installed: the NIST standards are left out (pip install pypdf)", flush=True)
        return
    folder = SOURCES / "nist"
    folder.mkdir(parents=True, exist_ok=True)
    for title, aliases, url in NIST:
        pdf = folder / url.rsplit("/", 1)[1]
        if not pdf.exists():
            print(f"fetching {url}", flush=True)
            request = urllib.request.Request(url, headers={"User-Agent": "offline-research-index-builder/0.1"})
            with urllib.request.urlopen(request, timeout=120) as response:
                pdf.write_bytes(response.read())
        pages = [page.extract_text() or "" for page in PdfReader(str(pdf)).pages]
        # Prose only: lines of pseudocode and tables are mostly symbols and numbers.
        kept = []
        for line in "\n".join(pages).splitlines():
            letters = sum(c.isalpha() or c == " " for c in line)
            if len(line) > 25 and letters / len(line) > 0.8:
                kept.append(line)
        text = clean_text(" ".join(kept))
        yield {"title": title, "url": url, "aliases": aliases, "popularity": 2e-6, "passages": chunk_text(text)}


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--out", type=Path, default=HERE / "work" / "index" / "ethereum.db")
    args = parser.parse_args()
    ensure_sources()
    upgrade_of = upgrades(SOURCES / "EIPs" / "EIPS")
    counts = {}

    def counted(name, documents):
        for document in documents:
            counts[name] = counts.get(name, 0) + 1
            yield document

    def everything():
        yield from counted("EIPs", proposals(SOURCES / "EIPs" / "EIPS", "EIP", "https://eips.ethereum.org/EIPS/", upgrade_of))
        yield from counted("ERCs", proposals(SOURCES / "ERCs" / "ERCS", "ERC", "https://eips.ethereum.org/EIPS/", upgrade_of))
        yield from counted("consensus specs", consensus_specs(SOURCES / "consensus-specs" / "specs"))
        yield from counted("ethereum.org pages", ethereum_org(SOURCES / "ethereum-org-website" / "public" / "content"))
        yield from counted("NIST standards", nist())

    meta = {
        "corpus": "ethereum",
        "source": "ethereum/EIPs@{} ethereum/ERCs@{} ethereum/consensus-specs@{} ethereum/ethereum-org-website@{} and NIST FIPS 203, 204, 205, SP 800-208".format(
            revision("EIPs"), revision("ERCs"), revision("consensus-specs"), revision("ethereum-org-website")),
        "dump_date": datetime.date.today().strftime("%Y%m%d"),
        "licence": "CC0-1.0 (EIPs, ERCs, consensus specs); MIT (ethereum.org); public domain (NIST)",
        "attribution": "Ethereum contributors; ethereum.org contributors; NIST",
        # The app uses this pack only where it matches a question well (see Retriever).
        "match": "strict",
    }
    articles, passages = write_index(args.out, everything(), meta)
    print(f"{args.out}: {articles} documents, {passages} passages, {args.out.stat().st_size / 1e6:.1f} MB")
    print("documents by source:", counts)
    print("proposals with a known network upgrade:", len(upgrade_of))


if __name__ == "__main__":
    main()
