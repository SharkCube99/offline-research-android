"""Query handling and search over the corpus indexes.

This is the reference for the retriever the Android app will implement in M3:
the same query construction and the same SQL have to be used there.

A question is searched through two channels, and their results are interleaved:

1. Name channel. Word n-grams of the question are looked up in the `names`
   table (article titles and redirect titles). "heart attack" finds the article
   "Myocardial infarction"; "vaccines" finds "Vaccine". The best passages of
   those articles are taken.
2. Passage channel. BM25 over all passages, requiring every content word of the
   question, and relaxing one word at a time if that finds too little.

The name channel finds the main article on a topic, which plain BM25 over
millions of passages tends to bury under longer, more specific articles. The
passage channel finds answers that sit inside articles about something else.
"""

import sqlite3
import sys
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from textproc import FUNCTION_WORDS, tokenize  # noqa: E402

# Words that are common in the way questions are phrased but say nothing about
# the topic. Unlike FUNCTION_WORDS they stay in the index, because in an article
# they can carry meaning.
QUERY_FILLER = frozenset("""
best way ways know need tell much many good thing things work works make makes use used using
get difference differences different versus vs explain
""".split())

# bm25() column weights for (title, aliases, body).
DEFAULT_WEIGHTS = (4.0, 3.0, 1.0)
# Longest article name looked for in a question, in words.
MAX_NAME_WORDS = 5
# Longest question looked up whole, in words ("why is the sky blue" is a redirect).
MAX_QUESTION_NAME_WORDS = 12
# How many articles the name channel may contribute, per corpus.
NAME_ARTICLES = 3
# How many articles one name may resolve to (titles first, then most viewed).
ARTICLES_PER_NAME = 2


def query_terms(question):
    """Lower-cased content words of the question, in order, without repeats."""
    tokens = tokenize(question)
    terms = [t for t in tokens if t not in FUNCTION_WORDS and t not in QUERY_FILLER and len(t) > 1]
    if not terms:
        # Function words are absent from passage bodies but still present in
        # titles ("The Who"), so a question made only of them can still match.
        terms = tokens
    return list(dict.fromkeys(terms))


# American and British spellings of the same word. The index holds whichever
# the article used, so a question with "center" must also find "centre".
_SPELLINGS = [("center", "centre"), ("meter", "metre"), ("kilometer", "kilometre"), ("liter", "litre"), ("theater", "theatre"), ("harbor", "harbour"), ("color", "colour"), ("neighborhood", "neighbourhood"), ("labor", "labour"), ("traveler", "traveller"), ("jewelry", "jewellery"), ("airplane", "aeroplane"), ("program", "programme"), ("gray", "grey"), ("tire", "tyre"), ("license", "licence"), ("defense", "defence"), ("aluminum", "aluminium"), ("fiber", "fibre")]
SPELLING = {a: b for a, b in _SPELLINGS} | {b: a for a, b in _SPELLINGS}


def _match(terms, operator):
    def one(term):
        other = SPELLING.get(term)
        return f'("{term}" OR "{other}")' if other else f'"{term}"'
    return f" {operator} ".join(one(term) for term in terms)


def to_fts_query(question):
    """The strictest FTS5 query tried for a question: every content word must be present."""
    return _match(query_terms(question), "AND")


def name_grams(question, max_words=MAX_NAME_WORDS):
    """[(start, length, key)]: word n-grams that could be an article name, longest first.

    An n-gram may contain function words ("war of the currents") but not start
    or end with one. Single filler words are not looked up.

    The whole question comes first when it is not already one of those n-grams:
    Wikipedia has redirects that are questions, such as "Why is the sky blue",
    and they point at the article that answers them.
    """
    tokens = tokenize(question)
    grams = []
    if 1 < len(tokens) <= MAX_QUESTION_NAME_WORDS and not is_plain_name(tokens, max_words):
        grams.append((0, len(tokens), " ".join(tokens)))
    for length in range(max_words, 0, -1):
        for start in range(len(tokens) - length + 1):
            gram = tokens[start:start + length]
            if gram[0] in FUNCTION_WORDS or gram[-1] in FUNCTION_WORDS:
                continue
            if length == 1 and (gram[0] in QUERY_FILLER or len(gram[0]) < 3):
                continue
            grams.append((start, length, " ".join(gram)))
    return grams


# How many two-part names are looked up per question in a special-purpose pack.
MAX_SPLIT_NAMES = 150


def split_names(question):
    """[(words, key)]: names made of two phrases of the question with other words between them.

    "Is there a pharmacy open late in central Nairobi?" never says "pharmacy in
    Nairobi", but a places pack lists that entry under "pharmacy nairobi" too.
    The first phrase has one or two words, the second up to three; neither
    starts or ends with a function word. Longest first, then in reading order.
    Used only for special-purpose packs, whose entries carry such names.
    """
    parts = [(start, length, key) for start, length, key in name_grams(question, max_words=3)
             if is_plain_name(key.split(), 3)]
    pairs = []
    for start1, length1, key1 in parts:
        if length1 > 2:
            continue
        for start2, length2, key2 in parts:
            if start1 + length1 < start2:  # touching phrases are an ordinary n-gram already
                pairs.append((length1 + length2, key1 + " " + key2))
    pairs.sort(key=lambda pair: -pair[0])
    return pairs[:MAX_SPLIT_NAMES]


def is_plain_name(gram, max_words=MAX_NAME_WORDS):
    """True for the n-grams that name_grams builds from parts of the question."""
    return len(gram) <= max_words and gram[0] not in FUNCTION_WORDS and gram[-1] not in FUNCTION_WORDS


def open_indexes(index_dir):
    """Opens every corpus database in index_dir, read-only. Returns {corpus: connection}."""
    return {path.stem: sqlite3.connect(f"file:{path.as_posix()}?mode=ro", uri=True)
            for path in sorted(Path(index_dir).glob("*.db"))}


def _ranked(con, fts_query, weights, limit, first=None, last=None):
    """[(passage_id, score)] best first. Optionally only passages first..last."""
    sql = "SELECT rowid, rank FROM passages_fts WHERE passages_fts MATCH ? AND rank MATCH ?"
    args = [fts_query, "bm25(%s, %s, %s)" % tuple(weights)]
    if first is not None:
        sql += " AND rowid BETWEEN ? AND ?"
        args += [first, last]
    return con.execute(sql + " ORDER BY rank LIMIT ?", args + [limit]).fetchall()


def is_strict(con):
    """True for a special-purpose pack (Ethereum texts, places), which marks itself
    in its meta table. It takes part only when one of its articles is named in the
    question or a passage holds every content word of it."""
    try:
        row = con.execute("SELECT value FROM meta WHERE key = 'match'").fetchone()
    except sqlite3.Error:
        return False
    return bool(row) and row[0] == "strict"


def passage_channel(con, terms, weights, limit, relax=True):
    """Passage ids, best first: all words required, then (unless relax is off) all but one."""
    rows = _ranked(con, _match(terms, "AND"), weights, limit)
    if len(rows) >= limit or len(terms) < 2 or not relax:
        return [passage_id for passage_id, _ in rows]
    found = {passage_id for passage_id, _ in rows}
    relaxed = {}
    if len(terms) == 2:
        candidates = _ranked(con, _match(terms, "OR"), weights, limit)
    else:
        candidates = []
        for skipped in range(len(terms)):
            rest = terms[:skipped] + terms[skipped + 1:]
            candidates += _ranked(con, _match(rest, "AND"), weights, limit)
    for passage_id, score in candidates:
        if passage_id not in found and score < relaxed.get(passage_id, 0.0):
            relaxed[passage_id] = score
    extra = sorted(relaxed, key=relaxed.get)[:limit - len(rows)]
    # Passages with every word always come before passages missing one.
    return [passage_id for passage_id, _ in rows] + extra


def name_channel(con, question, terms, weights, max_articles=NAME_ARTICLES, strict=False):
    """[(name_length, score, [passage ids])] for articles named in the question, best first."""
    claimed = set()   # word positions already matched by a longer name
    articles = {}     # article id -> (name length, first passage, passage count)
    for start, length, key in name_grams(question):
        positions = range(start, start + length)
        if all(position in claimed for position in positions):
            continue  # "heart" and "attack" add nothing once "heart attack" matched
        rows = []
        for variant in ((key, key[:-1]) if key.endswith("s") else (key,)):
            rows = con.execute(
                "SELECT a.id, a.first_passage_id, a.passage_count "
                "FROM names n JOIN articles a ON a.id = n.article_id "
                "WHERE n.key = ? ORDER BY n.is_title DESC, a.popularity DESC LIMIT ?",
                (variant, ARTICLES_PER_NAME)).fetchall()
            if rows:
                break
        if not rows:
            continue
        # A whole-question match does not claim its words: the question may also
        # be the title of a song or a film, and then the names inside it still count.
        if length > 1 and is_plain_name(key.split()):
            claimed.update(positions)
        for article_id, first, count in rows:
            articles.setdefault(article_id, (length, first, count))

    if strict:
        for length, key in split_names(question):
            rows = con.execute(
                "SELECT a.id, a.first_passage_id, a.passage_count "
                "FROM names n JOIN articles a ON a.id = n.article_id "
                "WHERE n.key = ? ORDER BY n.is_title DESC, a.popularity DESC LIMIT ?",
                (key, ARTICLES_PER_NAME)).fetchall()
            for article_id, first, count in rows:
                articles.setdefault(article_id, (length, first, count))

    any_term = _match(terms, "OR")
    scored = []
    for length, first, count in articles.values():
        # The article's passages that best match the whole question.
        best = _ranked(con, any_term, weights, 2, first, first + count - 1)
        if best:
            scored.append((-length, best[0][1], [passage_id for passage_id, _ in best]))
    scored.sort()
    return [(-neg_length, score, ids) for neg_length, score, ids in scored[:max_articles]]


def search(indexes, question, k=5, weights=DEFAULT_WEIGHTS, per_article=2, candidates=20):
    """Top-k passages across all corpora, as dicts with text and source metadata."""
    terms = query_terms(question)
    if not terms:
        return []

    named = []      # (-name length, score, corpus, [passage ids])
    by_corpus = {}  # corpus -> passage ids from the passage channel
    for corpus, con in indexes.items():
        for length, score, ids in name_channel(con, question, terms, weights, strict=is_strict(con)):
            named.append((-length, score, corpus, ids))
        by_corpus[corpus] = passage_channel(con, terms, weights, candidates, relax=not is_strict(con))
    named.sort()

    # Name channel order: the best passage of each named article, then the second best.
    name_list = [(corpus, ids[0], "name") for _, _, corpus, ids in named]
    name_list += [(corpus, ids[1], "name") for _, _, corpus, ids in named if len(ids) > 1]
    # Passage channel order: corpora take turns, each in its own BM25 order.
    passage_list = []
    for position in range(candidates):
        for corpus, ids in by_corpus.items():
            if position < len(ids):
                passage_list.append((corpus, ids[position], "passage"))

    merged = []
    for position in range(max(len(name_list), len(passage_list))):
        merged += name_list[position:position + 1] + passage_list[position:position + 1]

    results, seen, per_title = [], set(), {}
    for corpus, passage_id, channel in merged:
        if (corpus, passage_id) in seen:
            continue
        seen.add((corpus, passage_id))
        title, url, seq, body = indexes[corpus].execute(
            "SELECT a.title, a.url, p.seq, p.body FROM passages p "
            "JOIN articles a ON a.id = p.article_id WHERE p.id = ?", (passage_id,)).fetchone()
        if per_title.get((corpus, title), 0) >= per_article:
            continue  # one long article must not fill the whole list
        per_title[(corpus, title)] = per_title.get((corpus, title), 0) + 1
        results.append({"corpus": corpus, "passage_id": f"{corpus}:{passage_id}", "channel": channel,
                        "title": title, "url": url, "seq": seq,
                        "text": zlib.decompress(body).decode("utf-8")})
        if len(results) == k:
            break
    return results
