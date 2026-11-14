"""Focused foundation regression tests; extended suite is archived locally."""


from __future__ import annotations


from datetime import datetime, timezone


import pytest


from pydantic import ValidationError


from app.schemas import EventChangeType, JobState, MediaAsset, PersistedPostEvent, PostAreaId, PostCanonical, PostCategoryId, SignedUploadRequest, TimeMode, normalize_area_id, normalize_category_id


def _now() -> datetime:
    return datetime(2026, 11, 12, 8, 0, 0, tzinfo=timezone.utc)


def _valid_post_payload(**overrides: object) -> dict:
    base = {
        "title": "  Ví da màu nâu  ",
        "type": "LOST",
        "categoryId": "wallet",
        "category": "Ví tiền",
        "description": "  Ví da màu nâu rơi gần thư viện cơ sở 1.  ",
        "areaChoice": "KNOWN",
        "areaId": "library",
        "areaOtherText": "",
        "location": "Thư viện",
        "locationNote": "Tầng 2",
        "occurredAt": "08:00 - 12/11/2026",
        "timeMode": "EXACT",
        "exactAtMillis": 1762912800000,
        "rangeStartMillis": None,
        "rangeEndMillis": None,
        "timeNote": "",
        "status": "OPEN",
        "moderationStatus": "VISIBLE",
        "version": 1,
        "createdAt": _now(),
        "updatedAt": _now(),
        "authorId": "uid-student-1",
        "authorName": "Nguyen Van A",
        "contactEmail": "a@ut.edu.vn",
        "allowContact": True,
        "mediaAssetIds": [],
        "imageUrl": None,
        "imageUrls": [],
        "imagePublicId": None,
    }
    base.update(overrides)
    return base


def test_taxonomy_canonical_and_legacy_aliases() -> None:
    assert len(PostCategoryId) == 7
    assert len(PostAreaId) == 13

    assert normalize_category_id("phone") == PostCategoryId.ELECTRONICS
    assert normalize_category_id("laptop") == PostCategoryId.ELECTRONICS
    # Never map documents -> student_card
    assert normalize_category_id("documents") == PostCategoryId.DOCUMENTS
    assert normalize_category_id("student_card") == PostCategoryId.STUDENT_CARD
    assert normalize_category_id("made_up") is None

    assert normalize_area_id("Auditorium") == PostAreaId.AUDITORIUM
    assert normalize_area_id("auditorium") == PostAreaId.AUDITORIUM
    assert normalize_area_id("campus_1") == PostAreaId.CAMPUS_1
    assert normalize_area_id("unknown_place") is None


def test_post_canonical_positive_without_and_with_image() -> None:
    post = PostCanonical.model_validate(_valid_post_payload())
    assert post.title == "Ví da màu nâu"
    assert post.category_id == PostCategoryId.WALLET
    assert post.area_id == PostAreaId.LIBRARY

    url = "https://res.cloudinary.com/demo/image/upload/v1/lostlink/uid-1/asset-1.jpg"
    post_with_img = PostCanonical.model_validate(
        _valid_post_payload(
            areaChoice="OTHER",
            areaId="",
            areaOtherText="Sân thượng nhà xe",
            timeMode="RANGE",
            exactAtMillis=None,
            rangeStartMillis=1000,
            rangeEndMillis=2000,
            mediaAssetIds=["asset-1"],
            imageUrl=url,
            imageUrls=[url],
            imagePublicId="lostlink/uid-1/asset-1",
        )
    )
    assert post_with_img.media_asset_ids == ["asset-1"]
    assert post_with_img.area_id == ""

    post_unknown = PostCanonical.model_validate(
        _valid_post_payload(
            areaChoice="UNKNOWN",
            areaId="",
            timeMode="DESCRIBED",
            exactAtMillis=None,
            timeNote="Khoảng sáng nay",
        )
    )
    assert post_unknown.time_mode == TimeMode.DESCRIBED


@pytest.mark.parametrize(
    "bad_overrides",
    [
        {"categoryId": "made_up"},
        {"categoryId": "phone"},
        {"areaChoice": "KNOWN", "areaId": ""},
        {"areaChoice": "OTHER", "areaId": "library", "areaOtherText": "Khu X"},
        {"areaChoice": "OTHER", "areaId": "", "areaOtherText": "   "},
        {"areaChoice": "UNKNOWN", "areaId": "library"},
        {"title": "   "},
        {"description": ""},
        {"description": "   ngắn   "},
        {"timeMode": "EXACT", "exactAtMillis": None},
        {"timeMode": "RANGE", "rangeStartMillis": 2000, "rangeEndMillis": 1000},
        {"timeMode": "DESCRIBED", "timeNote": "   "},
        {"mediaAssetIds": [], "imageUrl": "https://res.cloudinary.com/x/y.jpg"},
        {"mediaAssetIds": [], "imageUrls": ["https://res.cloudinary.com/x/y.jpg"]},
        {"mediaAssetIds": [], "imagePublicId": "lostlink/u/a"},
        {
            "mediaAssetIds": ["asset-1"],
            "imageUrl": "http://insecure.example.com/a.jpg",
            "imageUrls": ["http://insecure.example.com/a.jpg"],
        },
        {
            "mediaAssetIds": ["asset-1"],
            "imageUrl": "https://res.cloudinary.com/x/a.jpg",
            "imageUrls": [],
        },
        # R1 & R3: moderationReason and embedding are forbidden on posts/{postId}
        {"moderationReason": "Spam post"},
        {"embedding": [0.1, 0.2, 0.3]},
    ],
)
def test_post_canonical_rejects_invalid_payloads(bad_overrides: dict) -> None:
    with pytest.raises(ValidationError):
        PostCanonical.model_validate(_valid_post_payload(**bad_overrides))


def test_persisted_post_event_factory_and_negative_cases() -> None:
    ev = PersistedPostEvent.build_initial(
        post_id="post-1",
        author_id="uid-1",
        post_version=1,
        change_type=EventChangeType.CREATE,
        created_at=_now(),
        image_asset_refs=["asset-1"],
    )
    dumped = ev.to_firestore_dict()
    assert "status" not in dumped
    assert dumped["searchJob"]["status"] == "PENDING"
    assert dumped["notifyJob"]["attempts"] == 0
    assert dumped["mediaCleanupJob"]["claimToken"] is None
    assert isinstance(dumped["createdAt"], datetime)

    # Round-trip from serialized dict
    reloaded = PersistedPostEvent.model_validate(dumped)
    assert reloaded == ev

    # Missing any job sub-map must fail
    missing_notify = dict(dumped)
    missing_notify.pop("notifyJob")
    with pytest.raises(ValidationError):
        PersistedPostEvent.model_validate(missing_notify)

    # Root status forbidden
    with_root_status = dict(dumped)
    with_root_status["status"] = "PENDING"
    with pytest.raises(ValidationError):
        PersistedPostEvent.model_validate(with_root_status)

    # Empty JobState dict must fail
    with pytest.raises(ValidationError):
        JobState.model_validate({})


@pytest.mark.parametrize("bad_post_id", [None, "", "   "])
def test_media_rejects_missing_or_empty_post_id(bad_post_id: object) -> None:
    with pytest.raises(ValidationError):
        SignedUploadRequest.model_validate({"postId": bad_post_id})

    with pytest.raises(ValidationError):
        MediaAsset.model_validate(
            {
                "assetId": "asset-1",
                "ownerUid": "uid-1",
                "postId": bad_post_id,
                "cloudinaryPublicId": "lostlink/uid-1/asset-1",
                "status": "UPLOADING",
                "createdAt": _now(),
            }
        )
