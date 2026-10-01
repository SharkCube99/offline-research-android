"""Text cleaning and chunking. Pure functions, no I/O."""

import re
import unicodedata

# Words with no topical meaning. They are left out of the full-text index (their
# posting lists are the largest and no query uses them) and dropped from queries.
FUNCTION_WORDS = frozenset("""
a about above after again all also am an and any are as at be because been before being below
between both but by can could did do does doing down during each few for from further had has
have having he her here hers him his how i if in into is it its just me more most my no nor not
now of off on once only or other our out over own same she should so some such than that the
their them then there these they this those through to too under until up very was we were what
when where which while who whom why will with would you your
""".split())

_TOKEN = re.compile(r"[^\W_]+", re.UNICODE)
_WHITESPACE = re.compile(r"\s+")
# A sentence ends at . ! or ? (plus any closing quotes or brackets) followed by
# whitespace and something that looks like the start of a new sentence.
_SENTENCE_BREAK = re.compile(r"(?<=[.!?])[\"')\]]*\s+(?=[\"'(\[]?[A-Z0-9])")


def clean_text(text):
    """Normalises Unicode and whitespace. The dump text is already free of wiki markup."""
    text = unicodedata.normalize("NFC", text)
    return _WHITESPACE.sub(" ", text).strip()


def tokenize(text):
    """Lower-cased alphanumeric tokens, split the way the FTS5 unicode61 tokenizer splits."""
    return _TOKEN.findall(text.lower())


def index_text(text):
    """The text as it goes into the full-text index: content words only."""
    return " ".join(token for token in tokenize(text) if token not in FUNCTION_WORDS)


def name_key(name):
    """A title or redirect as it is stored in, and looked up from, the names table."""
    return " ".join(tokenize(name))


def article_names(title, aliases):
    """[(key, is_title)] for an article: its title and each distinct redirect title."""
    keys = {name_key(title): 1}
    for alias in aliases.split(" | "):
        key = name_key(alias)
        if key and key not in keys:
            keys[key] = 0
    return [(key, is_title) for key, is_title in keys.items() if key]


def count_words(text):
    return len(text.split())


def split_sentences(text):
    return [s for s in _SENTENCE_BREAK.split(text) if s]


def chunk_text(text, target_words=250, max_words=300, min_tail_words=80):
    """Splits cleaned text into passages of about target_words words.

    Passages end on sentence boundaries. A passage is closed once it reaches
    target_words, or earlier if the next sentence would push it past max_words.
    A short final passage is folded into the one before it. A single sentence
    longer than max_words is cut on word boundaries.
    """
    pieces = []
    for sentence in split_sentences(text):
        words = sentence.split()
        if len(words) <= max_words:
            pieces.append(words)
        else:
            pieces.extend(words[i:i + max_words] for i in range(0, len(words), max_words))

    chunks = []
    current = []
    for words in pieces:
        if current and len(current) + len(words) > max_words:
            chunks.append(current)
            current = []
        current.extend(words)
        if len(current) >= target_words:
            chunks.append(current)
            current = []
    if current:
        if chunks and len(current) < min_tail_words:
            chunks[-1].extend(current)
        else:
            chunks.append(current)
    return [" ".join(words) for words in chunks]
