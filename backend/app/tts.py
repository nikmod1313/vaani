"""
Sarvam Bulbul v3 text-to-speech.

    POST https://api.sarvam.ai/text-to-speech
    header: api-subscription-key
    -> {"request_id": "...", "audios": ["<base64>"]}

Two rules this module exists to enforce:

  * THE KEY NEVER LEAVES THE SERVER. Anyone can unzip an APK and read its
    strings. The phone calls our backend; our backend calls Sarvam. That also
    makes the cache shared across every shop instead of per-device.

  * SYNTHESISE ONCE. A day's summary changes a handful of times. Keying the
    cache on hash(text + language + speaker) means a shopkeeper opening the
    dashboard ten times costs one synthesis, not ten.

Uses httpx if present, falling back to stdlib urllib so the module imports
and tests run on a bare Python.
"""

from __future__ import annotations

import base64
import hashlib
import json
import os
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path

ENDPOINT = "https://api.sarvam.ai/text-to-speech"
MODEL = "bulbul:v3"
DEFAULT_SPEAKER = "anushka"

# Bulbul v3 caps a single request at 2500 characters. A daily summary is
# nowhere near it, but the guard keeps a runaway template from 400-ing.
MAX_CHARS = 2500

# v3 supports pace but NOT pitch or loudness -- those are v2-only. Slightly
# under 1.0 because the text is mostly numbers, and numbers need room.
DEFAULT_PACE = 0.95

SUPPORTED_SPEAKERS = {
    "anushka", "abhilash", "manisha", "vidya", "arya", "karun", "hitesh",
    "aditya", "ritu", "priya", "neha", "rahul", "pooja", "rohan", "simran",
    "kavya", "amit", "dev", "ishita", "shreya", "ratan", "varun", "manan",
    "sumit", "roopa", "kabir", "aayan", "shubh", "ashutosh", "advait",
    "anand", "tanya", "tarun", "sunny", "mani", "gokul", "vijay", "shruti",
    "suhani", "mohit", "kavitha", "rehan", "soham", "rupali",
}


@dataclass
class Speech:
    path: Path
    cache_hit: bool
    characters: int
    request_id: str | None = None


class SarvamTTS:
    """
    Bulbul client with a content-addressed file cache.

    `available()` reports whether a key is actually configured, so callers can
    fall back to on-device TTS instead of raising at a shop counter.
    """

    def __init__(
        self,
        api_key: str | None = None,
        cache_dir: str | Path = "data/audio_cache",
        model: str = MODEL,
    ) -> None:
        self.api_key = api_key or os.environ.get("SARVAM_API_KEY")
        self.model = model
        self.cache_dir = Path(cache_dir)
        self.calls_ok = 0
        self.calls_failed = 0
        self.last_error: str | None = None

    def available(self) -> bool:
        return bool(self.api_key)

    # ------------------------------------------------------------------

    @staticmethod
    def cache_key(text: str, language_code: str, speaker: str, model: str) -> str:
        payload = f"{model}|{language_code}|{speaker}|{text}"
        return hashlib.sha256(payload.encode("utf-8")).hexdigest()[:32]

    def cached_path(self, text: str, language_code: str, speaker: str) -> Path:
        key = self.cache_key(text, language_code, speaker, self.model)
        return self.cache_dir / f"{key}.mp3"

    # ------------------------------------------------------------------

    def synthesize(
        self,
        text: str,
        language_code: str = "hi-IN",
        speaker: str = DEFAULT_SPEAKER,
        pace: float = DEFAULT_PACE,
    ) -> Speech:
        """
        Return a playable audio file, synthesising only on a cache miss.

        Raises RuntimeError when no key is configured -- callers should check
        `available()` first and take the on-device fallback instead.
        """
        if len(text) > MAX_CHARS:
            raise ValueError(
                f"text is {len(text)} chars; Bulbul v3 caps a request at {MAX_CHARS}"
            )
        if speaker not in SUPPORTED_SPEAKERS:
            raise ValueError(f"unknown speaker {speaker!r}")

        path = self.cached_path(text, language_code, speaker)
        if path.exists() and path.stat().st_size > 0:
            return Speech(path=path, cache_hit=True, characters=len(text))

        if not self.available():
            raise RuntimeError(
                "SARVAM_API_KEY is not set. Check available() and fall back to "
                "on-device TTS rather than calling this."
            )

        audio, request_id = self._request(text, language_code, speaker, pace)

        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(audio)
        return Speech(
            path=path, cache_hit=False, characters=len(text), request_id=request_id
        )

    # ------------------------------------------------------------------

    def _request(
        self, text: str, language_code: str, speaker: str, pace: float
    ) -> tuple[bytes, str | None]:
        body = {
            "text": text,
            "language_code": language_code,
            "model": self.model,
            "speaker": speaker,
            "pace": pace,
            "output_audio_codec": "mp3",
            "speech_sample_rate": 24000,
        }
        payload = json.dumps(body).encode("utf-8")
        headers = {
            "api-subscription-key": self.api_key,
            "Content-Type": "application/json",
        }

        try:
            data = self._post(payload, headers)
        except Exception as exc:
            self.calls_failed += 1
            self.last_error = f"{type(exc).__name__}: {exc}"
            raise

        audios = data.get("audios") or []
        if not audios:
            self.calls_failed += 1
            self.last_error = "response contained no audio"
            raise RuntimeError("Sarvam returned no audio")

        self.calls_ok += 1
        # The response is base64 text, not raw bytes -- decoding is on us.
        return base64.b64decode(audios[0]), data.get("request_id")

    def _post(self, payload: bytes, headers: dict) -> dict:
        try:
            import httpx  # noqa: F401

            return self._post_httpx(payload, headers)
        except ImportError:
            return self._post_urllib(payload, headers)

    def _post_httpx(self, payload: bytes, headers: dict) -> dict:
        import httpx

        response = httpx.post(ENDPOINT, content=payload, headers=headers, timeout=30.0)
        response.raise_for_status()
        return response.json()

    def _post_urllib(self, payload: bytes, headers: dict) -> dict:
        request = urllib.request.Request(
            ENDPOINT, data=payload, headers=headers, method="POST"
        )
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", "replace")[:300]
            raise RuntimeError(f"Sarvam {exc.code}: {detail}") from exc
