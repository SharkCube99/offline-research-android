"""Run with: python -m unittest discover -s data-pipeline/tests"""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import retrieval  # noqa: E402
from textproc import chunk_text, clean_text, count_words, split_sentences  # noqa: E402


def sentences(count, words_each):
    return " ".join("Word " + "word " * (words_each - 2) + "end." for _ in range(count))


class CleanTextTest(unittest.TestCase):
    def test_collapses_whitespace(self):
        self.assertEqual(clean_text("  a  b\n\n c\t"), "a b c")


class SplitSentencesTest(unittest.TestCase):
    def test_splits_on_sentence_ends(self):
        self.assertEqual(split_sentences("One. Two! Three? Four."), ["One.", "Two!", "Three?", "Four."])

    def test_does_not_split_inside_decimals_or_before_lowercase(self):
        self.assertEqual(split_sentences("It is 3.5 m tall, approx. the same."),
                         ["It is 3.5 m tall, approx. the same."])


class ChunkTextTest(unittest.TestCase):
    def test_short_text_is_one_chunk(self):
        self.assertEqual(chunk_text("Just one sentence."), ["Just one sentence."])

    def test_empty_text_gives_no_chunks(self):
        self.assertEqual(chunk_text(""), [])

    def test_chunks_are_within_bounds_and_lose_nothing(self):
        text = sentences(100, 20)  # 2,000 words
        chunks = chunk_text(text)
        self.assertEqual(" ".join(chunks), text)
        for chunk in chunks[:-1]:
            self.assertTrue(250 <= count_words(chunk) <= 300, count_words(chunk))

    def test_chunks_end_on_sentence_boundaries(self):
        for chunk in chunk_text(sentences(60, 17)):
            self.assertTrue(chunk.endswith("end."))

    def test_short_tail_is_folded_into_previous_chunk(self):
        chunks = chunk_text(sentences(14, 20))  # 280 words: 260 + a 20-word tail
        self.assertEqual(len(chunks), 1)

    def test_overlong_sentence_is_cut(self):
        chunks = chunk_text("word " * 700 + "end.")
        self.assertTrue(all(count_words(c) <= 300 + 80 for c in chunks))
        self.assertEqual(sum(count_words(c) for c in chunks), 701)


class QueryTest(unittest.TestCase):
    def test_stopwords_are_dropped(self):
        self.assertEqual(retrieval.query_terms("How do I treat a minor burn?"), ["treat", "minor", "burn"])

    def test_all_stopword_question_keeps_its_words(self):
        self.assertEqual(retrieval.query_terms("Who was he?"), ["who", "was", "he"])

    def test_terms_are_quoted_so_fts_syntax_cannot_leak(self):
        self.assertEqual(retrieval.to_fts_query('AND "NEAR" burn'), '"near" OR "burn"')

    def test_repeats_are_removed(self):
        self.assertEqual(retrieval.query_terms("burn burn Burn"), ["burn"])


if __name__ == "__main__":
    unittest.main()
