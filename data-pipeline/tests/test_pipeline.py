"""Run with: python -m unittest discover -s data-pipeline/tests"""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import retrieval  # noqa: E402
from textproc import article_names, chunk_text, clean_text, count_words, index_text, split_sentences  # noqa: E402


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
        self.assertEqual(retrieval.to_fts_query('AND "NEAR" burn'), '"near" AND "burn"')

    def test_repeats_are_removed(self):
        self.assertEqual(retrieval.query_terms("burn burn Burn"), ["burn"])


class NameTest(unittest.TestCase):
    def test_title_and_distinct_aliases_become_keys(self):
        self.assertEqual(article_names("Myocardial infarction", "Heart attack | Heart Attack | MI"),
                         [("myocardial infarction", 1), ("heart attack", 0), ("mi", 0)])

    def test_alias_equal_to_title_is_not_repeated(self):
        self.assertEqual(article_names("Jet engine", "Jet Engine"), [("jet engine", 1)])

    def test_punctuation_only_names_are_dropped(self):
        self.assertEqual(article_names("!!!", ""), [])

    def test_name_grams_are_longest_first(self):
        grams = [key for _, _, key in retrieval.name_grams("How do I recognise a heart attack?")]
        self.assertLess(grams.index("heart attack"), grams.index("heart"))

    def test_name_grams_do_not_start_or_end_with_a_function_word(self):
        grams = [key for _, _, key in retrieval.name_grams("the war of the currents")]
        self.assertIn("war of the currents", grams)
        self.assertNotIn("the war", grams)
        self.assertNotIn("war of", grams)

    def test_the_whole_question_is_looked_up_first_unless_it_is_already_a_plain_name(self):
        self.assertEqual(retrieval.name_grams("Why is the sky blue?")[0], (0, 5, "why is the sky blue"))
        self.assertEqual([key for _, _, key in retrieval.name_grams("heart attack")],
                         ["heart attack", "heart", "attack"])

    def test_split_names_join_two_phrases_with_words_between(self):
        keys = [key for _, key in retrieval.split_names("Is there a pharmacy open late in central Nairobi?")]
        self.assertIn("pharmacy nairobi", keys)
        self.assertIn("pharmacy central nairobi", keys)
        self.assertNotIn("nairobi pharmacy", keys)   # reading order only
        self.assertEqual(retrieval.split_names("pharmacy"), [])

    def test_single_filler_words_are_not_names(self):
        single = [key for _, length, key in retrieval.name_grams("best way to work") if length == 1]
        self.assertEqual(single, [])


class SharedVectorsTest(unittest.TestCase):
    """eval/query_vectors.json is also checked by the Kotlin unit tests, so both sides agree."""

    def test_vectors_match_the_reference_implementation(self):
        import json
        from textproc import tokenize
        path = Path(__file__).resolve().parents[1] / "eval" / "query_vectors.json"
        for vector in json.loads(path.read_text(encoding="utf-8")):
            question = vector["question"]
            self.assertEqual(tokenize(question), vector["tokens"], question)
            self.assertEqual(retrieval.query_terms(question), vector["terms"], question)
            self.assertEqual(retrieval.to_fts_query(question), vector["and_query"], question)
            self.assertEqual([list(g) for g in retrieval.name_grams(question)], vector["name_grams"], question)
            self.assertEqual(index_text(question), vector["index_text"], question)


class IndexTextTest(unittest.TestCase):
    def test_function_words_are_removed_and_text_lowercased(self):
        self.assertEqual(index_text("The Battle of Hastings was in 1066."), "battle hastings 1066")


if __name__ == "__main__":
    unittest.main()
