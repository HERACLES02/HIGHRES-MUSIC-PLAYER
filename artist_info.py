import json
import re
import time
import unicodedata
import urllib.error
from datetime import datetime, timezone
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen


USER_AGENT = "LocalMusicPlayer/1.0 (https://localhost.local)"
TIMEOUT_SECONDS = 8
RETRY_ATTEMPTS = 2
RETRY_BACKOFF_SECONDS = 0.6

MUSICBRAINZ_MIN_INTERVAL_SECONDS = 1.05
_last_musicbrainz_call = 0.0

PLACEHOLDER_ARTIST_NAMES = {
    "",
    "unknown",
    "unknown artist",
    "various",
    "various artists",
    "va",
    "n/a",
    "none",
}

MUSIC_RELEVANCE_KEYWORDS = (
    "singer",
    "songwriter",
    "musician",
    "rapper",
    "composer",
    "record producer",
    "producer",
    "vocalist",
    "band",
    "duo",
    "group",
    "idol",
    "orchestra",
    "guitarist",
    "drummer",
    "pianist",
    "dj ",
    " dj",
    "hip hop",
    "hip-hop",
    "rock band",
    "pop group",
    "recording artist",
    "music project",
    "girl group",
    "boy band",
    "record label",
)

_COLLAB_SPLIT_PATTERN = re.compile(r"\s+(?:x|vs\.?)\s+|\s*[,/&]\s*", flags=re.I)


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _normalize(value: str) -> str:
    decomposed = unicodedata.normalize("NFKD", value or "")
    without_accents = "".join(ch for ch in decomposed if not unicodedata.combining(ch))
    return re.sub(r"[^a-z0-9]+", "", without_accents.casefold())


def _strip_disambiguator(title: str) -> str:
    return re.sub(r"\s*\([^)]*\)\s*$", "", title or "").strip()


def _plausible_same_subject(candidate_title: str, queried_name: str) -> bool:
    # An independent Wikipedia text search has no external anchor confirming
    # identity, so before trusting it at all, require the page title to
    # actually resemble the name we searched for. Without this, fuzzy opensearch
    # can return a completely unrelated, well-documented namesake (e.g. a
    # short/uncommon artist name matching a famous person's surname) and a
    # loose keyword check on its description would wrongly accept it.
    title_normalized = _normalize(_strip_disambiguator(candidate_title))
    name_normalized = _normalize(queried_name)
    if not title_normalized or not name_normalized:
        return False
    return title_normalized.startswith(name_normalized) or name_normalized.startswith(title_normalized)


def _rate_limit_musicbrainz() -> None:
    global _last_musicbrainz_call
    now = time.monotonic()
    wait = MUSICBRAINZ_MIN_INTERVAL_SECONDS - (now - _last_musicbrainz_call)
    if wait > 0:
        time.sleep(wait)
    _last_musicbrainz_call = time.monotonic()


def _get_json(url: str) -> dict | list:
    if "musicbrainz.org" in url:
        _rate_limit_musicbrainz()

    req = Request(
        url,
        headers={
            "Accept": "application/json",
            "User-Agent": USER_AGENT,
        },
    )
    last_exc: Exception | None = None
    for attempt in range(RETRY_ATTEMPTS + 1):
        try:
            with urlopen(req, timeout=TIMEOUT_SECONDS) as response:
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            last_exc = exc
            if exc.code in (429, 500, 502, 503, 504) and attempt < RETRY_ATTEMPTS:
                time.sleep(RETRY_BACKOFF_SECONDS * (attempt + 1))
                continue
            raise
        except (urllib.error.URLError, TimeoutError) as exc:
            last_exc = exc
            if attempt < RETRY_ATTEMPTS:
                time.sleep(RETRY_BACKOFF_SECONDS * (attempt + 1))
                continue
            raise
    raise last_exc or RuntimeError("Request failed")


def is_placeholder_artist_name(name: str) -> bool:
    return (name or "").strip().casefold() in PLACEHOLDER_ARTIST_NAMES


def _clean_artist_name(name: str) -> str:
    cleaned = re.sub(r"\s*\((?:feat|ft|featuring)\.?.*?\)\s*", " ", name, flags=re.I)
    cleaned = re.split(r"\s+(?:feat|ft|featuring)\.?\s+", cleaned, maxsplit=1, flags=re.I)[0]
    return re.sub(r"\s+", " ", cleaned).strip()


def _candidate_names(name: str) -> list[str]:
    candidates = []
    for value in [name, _clean_artist_name(name)]:
        value = value.strip()
        if value and value not in candidates:
            candidates.append(value)

    # If this looks like a multi-artist collab string (e.g. "A x B", "A, B"),
    # also try the first artist alone as a lower-priority fallback candidate.
    primary = candidates[-1] if candidates else name.strip()
    parts = [part.strip() for part in _COLLAB_SPLIT_PATTERN.split(primary) if part.strip()]
    if len(parts) > 1 and parts[0] not in candidates:
        candidates.append(parts[0])

    return candidates


def _looks_like_music_page(description: str | None) -> bool:
    # Only trust Wikipedia's short structured description ("American rock
    # band", "English singer-songwriter"), not the full extract - the extract
    # frequently name-drops unrelated musicians in passing (e.g. an album
    # page's extract mentioning "English musician Paul McCartney"), which
    # previously caused completely unrelated pages to be accepted as matches.
    haystack = (description or "").casefold()
    if not haystack:
        return False
    return any(keyword in haystack for keyword in MUSIC_RELEVANCE_KEYWORDS)


def _musicbrainz_artist_payload(artist: dict) -> dict:
    tags = sorted(
        {
            str(tag.get("name"))
            for tag in artist.get("tags", [])
            if isinstance(tag, dict) and tag.get("name")
        }
    )
    genres = sorted(
        {
            str(genre.get("name"))
            for genre in artist.get("genres", [])
            if isinstance(genre, dict) and genre.get("name")
        }
    )
    aliases = sorted(
        {
            str(alias.get("name"))
            for alias in artist.get("aliases", [])
            if isinstance(alias, dict) and alias.get("name")
        }
    )
    life_span = artist.get("life-span") or {}
    area = artist.get("area") or artist.get("begin-area") or {}

    wikidata_id = None
    homepage = None
    for relation in artist.get("relations", []) or []:
        if not isinstance(relation, dict):
            continue
        url = (relation.get("url") or {}).get("resource")
        if not url:
            continue
        rel_type = relation.get("type")
        if rel_type == "wikidata" and not wikidata_id:
            match = re.search(r"/(Q\d+)\s*$", url)
            if match:
                wikidata_id = match.group(1)
        elif rel_type in ("official homepage", "homepage") and not homepage:
            homepage = url

    return {
        "id": artist.get("id"),
        "name": artist.get("name"),
        "sort_name": artist.get("sort-name"),
        "disambiguation": artist.get("disambiguation"),
        "type": artist.get("type"),
        "country": artist.get("country"),
        "area": area.get("name"),
        "begin": life_span.get("begin"),
        "end": life_span.get("end"),
        "ended": life_span.get("ended"),
        "tags": tags[:8],
        "genres": genres[:8],
        "aliases": aliases[:8],
        "wikidata_id": wikidata_id,
        "homepage": homepage,
        "url": f"https://musicbrainz.org/artist/{artist.get('id')}" if artist.get("id") else None,
    }


def _search_musicbrainz_artists(name: str, limit: int = 5) -> list[dict]:
    results = []
    seen = set()
    candidates = _candidate_names(name)
    primary_normalized = _normalize(candidates[0]) if candidates else _normalize(name)

    for candidate in candidates:
        params = urlencode(
            {
                "query": candidate,
                "fmt": "json",
                "limit": max(limit * 3, 10),
            }
        )
        data = _get_json(f"https://musicbrainz.org/ws/2/artist/?{params}")
        artists = data.get("artists", []) if isinstance(data, dict) else []
        for artist in artists:
            artist_id = artist.get("id")
            if not artist_id or artist_id in seen:
                continue
            seen.add(artist_id)
            payload = _musicbrainz_artist_payload(artist)
            exact_match = _normalize(payload.get("name") or "") == primary_normalized
            payload["score"] = 100 if exact_match else artist.get("score")
            payload["exact_match"] = exact_match
            results.append(payload)

    # Exact name matches first, then by MusicBrainz's own relevance score.
    results.sort(key=lambda item: (not item.get("exact_match"), -(item.get("score") or 0)))
    return results[:limit]


def _musicbrainz_artist_by_id(artist_id: str) -> dict:
    artist_id = (artist_id or "").strip()
    if not artist_id:
        return {}
    params = urlencode({"fmt": "json", "inc": "tags+genres+aliases+url-rels"})
    data = _get_json(f"https://musicbrainz.org/ws/2/artist/{quote(artist_id)}?{params}")
    return _musicbrainz_artist_payload(data) if isinstance(data, dict) else {}


def _best_musicbrainz_artist(name: str) -> dict:
    results = _search_musicbrainz_artists(name, limit=5)
    if not results:
        return {}
    best = results[0]
    # The search endpoint only returns a summary; fetch full detail (tags,
    # genres, aliases and the artist's own curated Wikidata link) for the
    # winning candidate so downstream lookups can be authoritative rather
    # than a second independent guess.
    try:
        detailed = _musicbrainz_artist_by_id(best.get("id"))
    except Exception:
        detailed = {}
    if detailed:
        detailed["score"] = best.get("score")
        detailed["exact_match"] = best.get("exact_match")
        return detailed
    return best


def _wikidata_enwiki_title(wikidata_id: str) -> str:
    wikidata_id = (wikidata_id or "").strip()
    if not wikidata_id:
        return ""
    params = urlencode(
        {
            "action": "wbgetentities",
            "ids": wikidata_id,
            "props": "sitelinks",
            "sitefilter": "enwiki",
            "format": "json",
        }
    )
    data = _get_json(f"https://www.wikidata.org/w/api.php?{params}")
    entities = data.get("entities", {}) if isinstance(data, dict) else {}
    entity = entities.get(wikidata_id) or {}
    sitelinks = entity.get("sitelinks", {}) or {}
    enwiki = sitelinks.get("enwiki") or {}
    return str(enwiki.get("title") or "")


def _wikipedia_candidates(name: str, limit: int = 5) -> list[dict]:
    results = []
    seen = set()
    for candidate in _candidate_names(name):
        search_params = urlencode(
            {
                "action": "opensearch",
                "search": candidate,
                "limit": limit,
                "namespace": 0,
                "format": "json",
            }
        )
        search_data = _get_json(f"https://en.wikipedia.org/w/api.php?{search_params}")
        titles = search_data[1] if isinstance(search_data, list) and len(search_data) > 1 else []
        descriptions = search_data[2] if isinstance(search_data, list) and len(search_data) > 2 else []
        urls = search_data[3] if isinstance(search_data, list) and len(search_data) > 3 else []
        for index, title in enumerate(titles):
            title = str(title)
            if not title or title in seen:
                continue
            seen.add(title)
            results.append(
                {
                    "title": title,
                    "description": descriptions[index] if index < len(descriptions) else "",
                    "url": urls[index] if index < len(urls) else "",
                }
            )
            if len(results) >= limit:
                return results
    return results


def _wikipedia_summary_by_title(title: str, require_music_relevance: bool = False) -> dict:
    title = (title or "").strip()
    if not title:
        return {}
    summary = _get_json(f"https://en.wikipedia.org/api/rest_v1/page/summary/{quote(title)}")
    if not isinstance(summary, dict):
        return {}
    if summary.get("type") == "disambiguation":
        return {}

    description = summary.get("description")
    extract = summary.get("extract")
    if require_music_relevance and not _looks_like_music_page(description):
        return {}

    urls = summary.get("content_urls") or {}
    desktop = urls.get("desktop") or {}
    thumbnail = summary.get("thumbnail") or {}
    return {
        "title": summary.get("title") or title,
        "description": description,
        "extract": extract,
        "image_url": thumbnail.get("source"),
        "url": desktop.get("page"),
    }


def _wikipedia_summary_by_search(name: str) -> dict:
    # Independent text search with no MusicBrainz/Wikidata anchor to confirm
    # identity - only accept a candidate whose title actually resembles the
    # queried name AND clearly reads as a music page. Otherwise it's better
    # to surface no info than a confident-looking but wrong homonymous page
    # (very common for short/common-word artist names).
    for candidate in _wikipedia_candidates(name, limit=5):
        if not _plausible_same_subject(candidate["title"], name):
            continue
        summary = _wikipedia_summary_by_title(candidate["title"], require_music_relevance=True)
        if summary:
            return summary
    return {}


def _match_confidence(mb: dict, wiki: dict) -> str:
    if mb and mb.get("wikidata_id") and wiki:
        return "verified"
    if mb and mb.get("exact_match"):
        return "high"
    if mb or wiki:
        return "low"
    return "none"


def search_artist_matches(artist_name: str) -> dict:
    artist_name = (artist_name or "").strip()
    if not artist_name:
        raise ValueError("Artist name is required")

    result = {
        "artist": artist_name,
        "musicbrainz": [],
        "wikipedia": [],
        "errors": [],
    }
    try:
        result["musicbrainz"] = _search_musicbrainz_artists(artist_name)
    except Exception as exc:
        result["errors"].append(f"MusicBrainz: {exc}")
    try:
        result["wikipedia"] = _wikipedia_candidates(
            result["musicbrainz"][0]["name"] if result["musicbrainz"] else artist_name
        )
    except Exception as exc:
        result["errors"].append(f"Wikipedia: {exc}")
    return result


def fetch_artist_info(
    artist_name: str,
    musicbrainz_id: str = "",
    wikipedia_title: str = "",
) -> dict:
    artist_name = (artist_name or "").strip()
    if not artist_name:
        raise ValueError("Artist name is required")

    result = {
        "artist": artist_name,
        "updated_at": _utc_now(),
        "musicbrainz": {},
        "wikipedia": {},
        "summary": "",
        "image_url": "",
        "external_url": "",
        "sources": [],
        "match_confidence": "none",
        "errors": [],
    }

    if not musicbrainz_id and not wikipedia_title and is_placeholder_artist_name(artist_name):
        result["errors"].append("Skipped lookup: not a real artist name")
        return result

    try:
        result["musicbrainz"] = _musicbrainz_artist_by_id(musicbrainz_id) if musicbrainz_id else _best_musicbrainz_artist(artist_name)
        if result["musicbrainz"]:
            result["sources"].append("MusicBrainz")
    except Exception as exc:
        result["errors"].append(f"MusicBrainz: {exc}")

    mb = result["musicbrainz"]

    try:
        if wikipedia_title:
            # User explicitly picked this page via the match picker - trust it.
            result["wikipedia"] = _wikipedia_summary_by_title(wikipedia_title)
        elif mb.get("wikidata_id"):
            # Best case: the exact MusicBrainz entity has a curated Wikidata
            # link, so the resolved Wikipedia article is guaranteed to be
            # about the same artist rather than a same-named lookalike.
            enwiki_title = _wikidata_enwiki_title(mb["wikidata_id"])
            result["wikipedia"] = _wikipedia_summary_by_title(enwiki_title) if enwiki_title else {}

        if not result["wikipedia"] and not wikipedia_title:
            result["wikipedia"] = _wikipedia_summary_by_search(mb.get("name") or artist_name)

        if result["wikipedia"]:
            result["sources"].append("Wikipedia")
    except Exception as exc:
        result["errors"].append(f"Wikipedia: {exc}")

    wiki = result["wikipedia"]
    result["summary"] = wiki.get("extract") or _musicbrainz_sentence(artist_name, mb)
    result["image_url"] = wiki.get("image_url") or ""
    result["external_url"] = wiki.get("url") or mb.get("homepage") or mb.get("url") or ""
    result["match_confidence"] = _match_confidence(mb, wiki)
    return result


def _musicbrainz_sentence(artist_name: str, mb: dict) -> str:
    if not mb:
        return ""
    pieces = [mb.get("name") or artist_name]
    artist_type = mb.get("type") or "artist"
    article = "an" if artist_type[:1].casefold() in "aeiou" else "a"
    area = mb.get("area") or mb.get("country")
    location = f" from {area}" if area else ""
    pieces.append(f"is listed on MusicBrainz as {article} {artist_type}{location}")
    descriptors = mb.get("genres") or mb.get("tags") or []
    if descriptors:
        label = "genres" if mb.get("genres") else "tags"
        pieces.append(f"with {label} including {', '.join(descriptors[:4])}")
    return " ".join(pieces) + "."
