import pytest

import artist_info as ai


def test_normalize_strips_punctuation_case_and_accents():
    assert ai._normalize("Beyoncé") == "beyonce"
    assert ai._normalize("E.T.A.") == "eta"
    assert ai._normalize("  Silverstein  ") == "silverstein"
    assert ai._normalize("") == ""


def test_is_placeholder_artist_name():
    assert ai.is_placeholder_artist_name("Various Artists")
    assert ai.is_placeholder_artist_name("  UNKNOWN  ")
    assert ai.is_placeholder_artist_name("")
    assert not ai.is_placeholder_artist_name("Metallica")


def test_candidate_names_strips_feat_and_splits_collabs():
    candidates = ai._candidate_names("Artist A feat. Artist B")
    assert "Artist A feat. Artist B" in candidates
    assert "Artist A" in candidates

    collab_candidates = ai._candidate_names("wenszy x wusia")
    assert "wenszy" in collab_candidates


def test_strip_disambiguator():
    assert ai._strip_disambiguator("Silverstein (band)") == "Silverstein"
    assert ai._strip_disambiguator("Memory") == "Memory"


def test_plausible_same_subject_accepts_close_titles():
    assert ai._plausible_same_subject("Silverstein (band)", "Silverstein")
    assert ai._plausible_same_subject("NewJeans", "newjeans")


def test_plausible_same_subject_rejects_unrelated_namesake():
    # Regression test: "wenszy" must not match "Wendy Carlos" just because
    # opensearch fuzzily returned it as a top hit.
    assert not ai._plausible_same_subject("Wendy Carlos", "wenszy")


def test_looks_like_music_page_uses_description_only():
    assert ai._looks_like_music_page("American rock band")
    assert ai._looks_like_music_page("English singer-songwriter")
    assert not ai._looks_like_music_page("2007 studio album by Paul McCartney")
    assert not ai._looks_like_music_page("")
    assert not ai._looks_like_music_page(None)


def test_musicbrainz_sentence_grammar():
    sentence = ai._musicbrainz_sentence("Memory", {"name": "Memory", "area": "Buenos Aires"})
    assert sentence == "Memory is listed on MusicBrainz as an artist from Buenos Aires."

    sentence_with_type = ai._musicbrainz_sentence(
        "Silverstein",
        {"name": "Silverstein", "type": "Group", "area": "Canada", "genres": ["emo", "post-hardcore"]},
    )
    assert sentence_with_type == (
        "Silverstein is listed on MusicBrainz as a Group from Canada "
        "with genres including emo, post-hardcore."
    )


def test_musicbrainz_sentence_empty_without_data():
    assert ai._musicbrainz_sentence("Anything", {}) == ""


def test_match_confidence_levels():
    assert ai._match_confidence({"wikidata_id": "Q1"}, {"title": "x"}) == "verified"
    assert ai._match_confidence({"exact_match": True}, {}) == "high"
    assert ai._match_confidence({"name": "x"}, {}) == "low"
    assert ai._match_confidence({}, {}) == "none"


def test_fetch_artist_info_skips_placeholder_names():
    info = ai.fetch_artist_info("Various Artists")
    assert info["match_confidence"] == "none"
    assert info["musicbrainz"] == {}
    assert info["errors"] == ["Skipped lookup: not a real artist name"]


def test_fetch_artist_info_requires_a_name():
    with pytest.raises(ValueError):
        ai.fetch_artist_info("")
