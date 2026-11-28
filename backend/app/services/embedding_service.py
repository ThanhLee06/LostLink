"""Dịch vụ tạo embedding ngữ nghĩa tiếng Việt (hỗ trợ Vectorizer nội bộ 0 đồng + Gemini API)."""
from __future__ import annotations

import hashlib
import math
import re
from typing import Any, Dict, List, Optional, Set, Tuple
import unicodedata
import httpx

from app.core.config import Settings, get_settings
from app.core.errors import redact_sensitive_text
from app.schemas.taxonomy import AREA_LABELS, CATEGORY_LABELS, normalize_area_id, normalize_category_id

_GEMINI_EMBED_URL = "https://generativelanguage.googleapis.com/v1beta/models/{model}:embedContent"
_POST_TYPE_LABELS = {"LOST": "Mất đồ", "FOUND": "Nhặt được đồ"}

# Nhóm khái niệm đồng nghĩa đồ thất lạc & khu vực trong trường
CONCEPT_GROUPS: Dict[str, Tuple[str, ...]] = {
    "concept_wallet": ("vi", "bop", "tui tien", "vi tien", "bop tien", "vi da", "bop da", "wallet"),
    "concept_student_card": ("the sinh vien", "the sv", "mssv", "student card"),
    "concept_documents": ("giay to", "cccd", "cmnd", "can cuoc", "bang lai", "cavet", "ho chieu"),
    "concept_phone": ("dien thoai", "dt", "dtdd", "smartphone", "iphone", "samsung", "oppo", "xiaomi"),
    "concept_laptop": ("laptop", "may tinh", "may tinh xach tay", "macbook", "notebook", "ipad", "tai nghe", "airpods", "sac", "pin du phong", "chuot"),
    "concept_keys": ("chia khoa", "khoa xe", "smartkey", "moc khoa", "chia phong"),
    "concept_bag": ("balo", "ba lo", "tui xach", "cap sach", "tui deo", "vali"),
    "concept_library": ("thu vien", "library", "phong doc"),
    "concept_canteen": ("can tin", "canteen", "nha an"),
    "concept_parking": ("bai giu xe", "bai xe", "nha xe", "ham xe"),
    "concept_dorm": ("ky tuc xa", "ktx", "dorm"),
    "concept_auditorium": ("hoi truong", "auditorium"),
}

_KNOWN_VOCAB: Set[str] = {
    "vi", "bop", "tui", "tien", "da", "wallet", "the", "sinh", "vien", "sv", "mssv",
    "giay", "to", "cccd", "cmnd", "can", "cuoc", "bang", "lai", "cavet", "dien", "thoai",
    "dt", "iphone", "samsung", "oppo", "xiaomi", "laptop", "may", "tinh", "xach", "tay",
    "macbook", "ipad", "tai", "nghe", "airpods", "sac", "pin", "chuot", "chia", "khoa",
    "xe", "smartkey", "moc", "phong", "balo", "cap", "sach", "vali", "thu", "library",
    "tin", "canteen", "nha", "bai", "giu", "ham", "ky", "tuc", "xa", "ktx", "hoi", "truong",
    "mat", "nhat", "tim", "roi", "quen", "den", "nau", "xanh", "trang", "do", "vang", "bac",
}
_VOWELS = frozenset("aeiouy")


class EmbeddingError(RuntimeError):
    """Lỗi khi tạo hoặc chuẩn hóa vector embedding."""


def _fold_ascii(text: str) -> str:
    if not text:
        return ""
    text = text.replace("đ", "d").replace("Đ", "D")
    decomposed = unicodedata.normalize("NFD", text)
    return "".join(c for c in decomposed if unicodedata.category(c) != "Mn").lower()


def _try_vocab_or_telex(word: str) -> Optional[str]:
    if word in _KNOWN_VOCAB:
        return word
    # Khử phím dấu Telex/VNI bị kẹt ở cuối từ (vd: vis -> vi, ther -> the, khoas -> khoa)
    if len(word) >= 3 and word[-1] in "sfrxjw123456789":
        base = re.sub(r"[sfrxjw1-9]+$", "", word)
        if len(base) >= 2 and any(c in _VOWELS for c in base) and base in _KNOWN_VOCAB:
            return base
    return None


def _levenshtein_1(a: str, b: str) -> bool:
    if abs(len(a) - len(b)) > 1:
        return False
    if len(a) == len(b):
        diffs = [i for i in range(len(a)) if a[i] != b[i]]
        if len(diffs) == 1:
            return True
        if len(diffs) == 2 and diffs[1] == diffs[0] + 1 and a[diffs[0]] == b[diffs[1]] and a[diffs[1]] == b[diffs[0]]:
            return True
        return False
    short, long = (a, b) if len(a) < len(b) else (b, a)
    i = j = diffs = 0
    while i < len(short) and j < len(long):
        if short[i] == long[j]:
            i += 1
            j += 1
        else:
            diffs += 1
            if diffs > 1:
                return False
            j += 1
    return True


def canonicalize_vn_token(token: str) -> str:
    """Chuẩn hóa từ tiếng Việt: gộp chữ lặp (viiiiii->vi), khử Telex (vis->vi), sửa đảo chữ (vsi->vi)."""
    w = _fold_ascii(token).strip()
    if not w:
        return ""
    if w in _KNOWN_VOCAB:
        return w
    collapsed = re.sub(r"([a-z])\1+", r"\1", w)
    hit = _try_vocab_or_telex(collapsed)
    if hit:
        return hit
    # Thử hoán vị 2 chữ cái liền kề (vd: vsi -> vis -> vi, baol -> balo)
    for i in range(len(collapsed) - 1):
        swapped = collapsed[:i] + collapsed[i + 1] + collapsed[i] + collapsed[i + 2:]
        hit = _try_vocab_or_telex(swapped)
        if hit:
            return hit
    if len(collapsed) >= 4:
        for cand in sorted(_KNOWN_VOCAB):
            if len(cand) >= 4 and _levenshtein_1(collapsed, cand):
                return cand
    return collapsed


def expand_search_term_groups(terms: List[str]) -> List[Tuple[str, Set[str]]]:
    """Gom cụm từ và mở rộng tập từ đồng nghĩa/sửa lỗi cho mỗi yêu cầu tìm kiếm."""
    if not terms:
        return []
    canons = [canonicalize_vn_token(t) for t in terms]
    groups: List[Tuple[str, Set[str]]] = []
    i = 0
    n = len(terms)
    while i < n:
        matched_phrase = False
        for span in (3, 2):
            if i + span <= n:
                phrase = " ".join(canons[i : i + span])
                for members in CONCEPT_GROUPS.values():
                    if phrase in members:
                        eq = set(members) | {phrase, " ".join(terms[i : i + span])}
                        groups.append((phrase, eq))
                        i += span
                        matched_phrase = True
                        break
            if matched_phrase:
                break
        if matched_phrase:
            continue
        raw_t = terms[i]
        c_t = canons[i]
        eq = {raw_t, c_t}
        for members in CONCEPT_GROUPS.values():
            if c_t in members or raw_t in members:
                eq.update(members)
        groups.append((raw_t, eq))
        i += 1
    return groups


def build_post_embedding_text(post_data: Dict[str, Any]) -> str:
    """Ghép văn bản ngữ nghĩa tiếng Việt từ tiêu đề, mô tả, danh mục và khu vực."""
    parts: List[str] = []
    post_type = str(post_data.get("type") or "").strip().upper()
    if post_type in _POST_TYPE_LABELS:
        parts.append(f"Loại tin: {_POST_TYPE_LABELS[post_type]}")
    title = str(post_data.get("title") or "").strip()
    if title:
        parts.append(f"Tiêu đề: {title}")
    description = str(post_data.get("description") or "").strip()
    if description:
        parts.append(f"Mô tả: {description}")
    cat = normalize_category_id(post_data.get("categoryId") if isinstance(post_data.get("categoryId"), str) else None)
    if cat is not None:
        parts.append(f"Danh mục: {CATEGORY_LABELS[cat]}")
    area = normalize_area_id(post_data.get("areaId") if isinstance(post_data.get("areaId"), str) else None)
    area_other = str(post_data.get("areaOtherText") or "").strip()
    if area is not None:
        parts.append(f"Khu vực: {AREA_LABELS[area]}")
    elif area_other:
        parts.append(f"Khu vực: {area_other}")
    location = str(post_data.get("location") or "").strip()
    if location:
        parts.append(f"Vị trí cụ thể: {location}")
    return "\n".join(parts)


def _l2_normalize(values: List[float]) -> List[float]:
    norm = math.sqrt(sum(v * v for v in values))
    if not math.isfinite(norm) or norm <= 0.0:
        raise EmbeddingError("Vector embedding có chuẩn bằng 0 hoặc không hợp lệ.")
    return [v / norm for v in values]


def local_embed_text(text: str, dimensions: int = 768) -> List[float]:
    """Tạo vector ngữ nghĩa 768 chiều nội bộ (0 đồng, <0.5ms) kết hợp khái niệm đồng nghĩa và n-gram."""
    folded = _fold_ascii(text)
    raw_words = re.findall(r"\w+", folded, re.UNICODE)
    if not raw_words:
        raise EmbeddingError("Văn bản đầu vào cho embedding bị rỗng.")
    canons = [canonicalize_vn_token(w) for w in raw_words if w]
    joined_canon = " ".join(canons)

    features: List[Tuple[str, float]] = []
    for cid, members in CONCEPT_GROUPS.items():
        if any(m in canons if " " not in m else m in joined_canon for m in members):
            features.append((f"concept:{cid}", 4.5))
    for c in canons:
        features.append((f"tok:{c}", 2.0))
        padded = f"^{c}$"
        for k in range(len(padded) - 2):
            features.append((f"tri:{padded[k:k+3]}", 0.45))

    vec = [0.0] * dimensions
    for feat, weight in features:
        digest = hashlib.blake2b(feat.encode("utf-8"), digest_size=16).digest()
        for slot in range(0, 16, 4):
            val = int.from_bytes(digest[slot : slot + 4], "little")
            idx = val % dimensions
            sign = 1.0 if (val & (1 << 31)) == 0 else -1.0
            vec[idx] += sign * weight
    return _l2_normalize(vec)


async def embed_text(
    text: str,
    *,
    task_type: str = "RETRIEVAL_DOCUMENT",
    settings: Optional[Settings] = None,
    timeout: float = 15.0,
) -> List[float]:
    """Tạo vector embedding: ưu tiên Vectorizer nội bộ 0 đồng khi không đặt GEMINI_API_KEY."""
    cleaned = text.strip()
    if not cleaned:
        raise EmbeddingError("Văn bản đầu vào cho embedding bị rỗng.")
    cfg = settings or get_settings()
    api_key = cfg.effective_gemini_api_key
    if not api_key:
        return local_embed_text(cleaned, cfg.embedding_dimensions)
    url = _GEMINI_EMBED_URL.format(model=cfg.embedding_model)
    payload = {
        "content": {"parts": [{"text": cleaned}]},
        "taskType": task_type,
        "outputDimensionality": cfg.embedding_dimensions,
    }
    try:
        async with httpx.AsyncClient(timeout=max(0.5, timeout)) as client:
            resp = await client.post(url, headers={"x-goog-api-key": api_key}, json=payload)
    except httpx.HTTPError as exc:
        raise EmbeddingError(redact_sensitive_text(f"Lỗi kết nối Gemini: {exc}")) from exc
    if resp.status_code >= 400:
        raise EmbeddingError(redact_sensitive_text(f"Gemini trả về HTTP {resp.status_code}: {resp.text}"))
    try:
        raw_values = resp.json()["embedding"]["values"]
    except (ValueError, KeyError, TypeError) as exc:
        raise EmbeddingError("Phản hồi Gemini thiếu trường embedding.values.") from exc
    if not isinstance(raw_values, list) or len(raw_values) != cfg.embedding_dimensions:
        raise EmbeddingError(f"Số chiều vector không khớp ({cfg.embedding_dimensions}).")
    values: List[float] = []
    for v in raw_values:
        if isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(float(v)):
            raise EmbeddingError("Giá trị phần tử vector không hợp lệ.")
        values.append(float(v))
    return _l2_normalize(values)


async def embed_post(post_data: Dict[str, Any], *, settings: Optional[Settings] = None) -> List[float]:
    """Tạo embedding cho một bài đăng LostLink."""
    return await embed_text(build_post_embedding_text(post_data), task_type="RETRIEVAL_DOCUMENT", settings=settings)


async def embed_query(
    query: str,
    *,
    settings: Optional[Settings] = None,
    timeout: float = 4.0,
) -> List[float]:
    """Tạo embedding cho chuỗi truy vấn tìm kiếm."""
    return await embed_text(query, task_type="RETRIEVAL_QUERY", settings=settings, timeout=timeout)
