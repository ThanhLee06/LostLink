const {
  Timestamp,
  assertFails,
  assertSucceeds,
  collection,
  createPostAtomically,
  deleteDoc,
  deletePostAtomically,
  doc,
  getDoc,
  getDocs,
  limit,
  makePostData,
  makePostEventData,
  makeTombstoneData,
  orderBy,
  query,
  rawAuthedDb,
  seedAccountState,
  seedCanonicalPost,
  seedDoc,
  seedMediaAsset,
  seedUserProfile,
  setDoc,
  setupRulesTestEnv,
  studentDb,
  unauthDb,
  updateDoc,
  updatePostAtomically,
  where,
  writeBatch,
} = require('./helpers');
const { runTransaction } = require('firebase/firestore');

let testEnv;

beforeAll(async () => {
  testEnv = await setupRulesTestEnv();
});

beforeEach(async () => {
  await testEnv.clearFirestore();
});

afterAll(async () => {
  await testEnv.cleanup();
});

describe('posts security rules (Contract §3.1, §3.3, §3.4, §3.6, §6)', () => {
  describe('Auth and @ut.edu.vn verification gate', () => {
    test('rejects unauthenticated read and write', async () => {
      await seedCanonicalPost(testEnv, 'post-public');
      const anon = unauthDb(testEnv);

      await assertFails(getDoc(doc(anon, 'posts', 'post-public')));
      await assertFails(createPostAtomically(anon, 'post-anon'));
    });

    test('rejects unverified @ut.edu.vn email and non-student domains', async () => {
      const unverified = rawAuthedDb(testEnv, 'u-unverified', {
        email: 'student1@ut.edu.vn',
        email_verified: false,
      });
      const gmail = rawAuthedDb(testEnv, 'u-gmail', {
        email: 'student1@gmail.com',
        email_verified: true,
      });
      const lookalike = rawAuthedDb(testEnv, 'u-lookalike', {
        email: 'student1@fakeut.edu.vn',
        email_verified: true,
      });
      const suffixSpoof = rawAuthedDb(testEnv, 'u-evil', {
        email: 'student1@ut.edu.vn.evil.com',
        email_verified: true,
      });

      await assertFails(
        createPostAtomically(unverified, 'post-unv', {
          authorId: 'u-unverified',
          contactEmail: 'student1@ut.edu.vn',
        })
      );
      await assertFails(
        createPostAtomically(gmail, 'post-gmail', {
          authorId: 'u-gmail',
          contactEmail: 'student1@gmail.com',
        })
      );
      await assertFails(
        createPostAtomically(lookalike, 'post-lookalike', {
          authorId: 'u-lookalike',
          contactEmail: 'student1@fakeut.edu.vn',
        })
      );
      await assertFails(
        createPostAtomically(suffixSpoof, 'post-evil', {
          authorId: 'u-evil',
          contactEmail: 'student1@ut.edu.vn.evil.com',
        })
      );
    });
  });

  describe('B0-T01: Atomic create and canonical schema validation', () => {
    test('allows atomic create of canonical post + v1 event', async () => {
      const db = studentDb(testEnv, 'student-1', 'student1@ut.edu.vn');
      await assertSucceeds(createPostAtomically(db, 'post-ok'));

      const snap = await assertSucceeds(getDoc(doc(db, 'posts', 'post-ok')));
      expect(snap.exists()).toBe(true);
      expect(snap.data().status).toBe('OPEN');
      expect(snap.data().moderationStatus).toBe('VISIBLE');
      expect(snap.data().version).toBe(1);

      // Optional id field is accepted only when matching postId
      await assertSucceeds(
        createPostAtomically(db, 'post-with-id', { id: 'post-with-id' })
      );
      await assertFails(
        createPostAtomically(db, 'post-bad-id', { id: 'different-id' })
      );
    });

    test('denies standalone post create without post_events/{postId}_v1', async () => {
      const db = studentDb(testEnv, 'student-1', 'student1@ut.edu.vn');
      await assertFails(setDoc(doc(db, 'posts', 'post-no-ev'), makePostData()));
    });

    test('denies create when pairing with pre-existing event instead of atomic create', async () => {
      const db = studentDb(testEnv, 'student-1', 'student1@ut.edu.vn');
      await seedDoc(testEnv, 'post_events', 'post-pre_v1', {
        ...makePostEventData({ postId: 'post-pre' }),
        createdAt: Timestamp.now(),
      });

      await assertFails(setDoc(doc(db, 'posts', 'post-pre'), makePostData()));
    });

    test('denies forbidden fields moderationReason (R1), embedding (R3), and unknown keys', async () => {
      const db = studentDb(testEnv, 'student-1', 'student1@ut.edu.vn');

      await assertFails(
        createPostAtomically(db, 'post-r1', { moderationReason: 'Leak reason' })
      );
      await assertFails(
        createPostAtomically(db, 'post-r3', { embedding: [0.1, 0.2] })
      );
      await assertFails(
        createPostAtomically(db, 'post-extra', { extraField: 'nope' })
      );
    });

    test('enforces title, description, taxonomy, areaChoice, and timeMode invariants', async () => {
      const db = studentDb(testEnv, 'student-1', 'student1@ut.edu.vn');

      // Whitespace-only / oversized title / short description
      await assertFails(createPostAtomically(db, 'p-title-ws', { title: '   ' }));
      await assertFails(
        createPostAtomically(db, 'p-title-long', { title: 'a'.repeat(81) })
      );
      await assertFails(
        createPostAtomically(db, 'p-desc-short', { description: 'Quá ngắn' })
      );

      // Legacy category aliases rejected on canonical write
      await assertFails(
        createPostAtomically(db, 'p-cat-legacy', { categoryId: 'phone' })
      );

      // Legacy capitalized Auditorium & mismatched areaChoice/areaId rejected
      await assertFails(
        createPostAtomically(db, 'p-area-cap', { areaId: 'Auditorium' })
      );
      await assertFails(
        createPostAtomically(db, 'p-area-known-empty', {
          areaChoice: 'KNOWN',
          areaId: '',
        })
      );
      await assertFails(
        createPostAtomically(db, 'p-area-other-empty', {
          areaChoice: 'OTHER',
          areaId: '',
          areaOtherText: '   ',
        })
      );
      await assertFails(
        createPostAtomically(db, 'p-area-unknown-id', {
          areaChoice: 'UNKNOWN',
          areaId: 'library',
        })
      );

      // Valid OTHER and UNKNOWN area choices succeed
      await assertSucceeds(
        createPostAtomically(db, 'p-area-other-ok', {
          areaChoice: 'OTHER',
          areaId: '',
          areaOtherText: 'Cổng sau',
        })
      );
      await assertSucceeds(
        createPostAtomically(db, 'p-area-unknown-ok', {
          areaChoice: 'UNKNOWN',
          areaId: '',
          areaOtherText: '',
        })
      );

      // Invalid timeMode combinations
      await assertFails(
        createPostAtomically(db, 'p-time-exact-null', {
          timeMode: 'EXACT',
          exactAtMillis: null,
        })
      );
      await assertFails(
        createPostAtomically(db, 'p-time-range-bad', {
          timeMode: 'RANGE',
          rangeStartMillis: 2000,
          rangeEndMillis: 1000,
        })
      );
      await assertFails(
        createPostAtomically(db, 'p-time-desc-empty', {
          timeMode: 'DESCRIBED',
          timeNote: '   ',
        })
      );
      await assertSucceeds(
        createPostAtomically(db, 'p-time-unknown-ok', {
          timeMode: 'UNKNOWN',
          exactAtMillis: null,
        })
      );
    });

    test('enforces initial status, moderationStatus, version, and privacy contactEmail snapshot', async () => {
      const db = studentDb(testEnv, 'student-1', 'student1@ut.edu.vn');

      await assertFails(
        createPostAtomically(db, 'p-status-closed', { status: 'CLOSED' })
      );
      await assertFails(
        createPostAtomically(db, 'p-status-active', { status: 'ACTIVE' })
      );
      await assertFails(
        createPostAtomically(db, 'p-mod-hidden', { moderationStatus: 'HIDDEN' })
      );
      await assertFails(createPostAtomically(db, 'p-ver-2', { version: 2 }));

      // allowContact false requires empty contactEmail
      await assertFails(
        createPostAtomically(db, 'p-contact-leak', {
          allowContact: false,
          contactEmail: 'student1@ut.edu.vn',
        })
      );
      await assertSucceeds(
        createPostAtomically(db, 'p-contact-off-ok', {
          allowContact: false,
          contactEmail: '',
        })
      );

      // allowContact true cannot spoof another email or override users/{uid}.allowEmailContact=false
      await assertFails(
        createPostAtomically(db, 'p-contact-spoof', {
          allowContact: true,
          contactEmail: 'other@ut.edu.vn',
        })
      );
      await seedUserProfile(testEnv, 'student-1', { allowEmailContact: false });
      await assertFails(
        createPostAtomically(db, 'p-contact-profile-off', {
          allowContact: true,
          contactEmail: 'student1@ut.edu.vn',
        })
      );
    });
  });

  describe('C3-R1: Draft post_reservations ownership guard on create (Contract §3.6)', () => {
    test('allows no-reservation create and owner create with profile + ACTIVE account_state + READY media + reservation', async () => {
      const ownerDb = studentDb(testEnv, 'student-1', 'student1@ut.edu.vn');

      // No reservation present -> no-image create succeeds
      await assertSucceeds(createPostAtomically(ownerDb, 'draft-no-res'));

      // Owner reservation (without media) succeeds
      await seedDoc(testEnv, 'post_reservations', 'draft-owner-no-img', {
        postId: 'draft-owner-no-img',
        ownerUid: 'student-1',
        createdAt: Timestamp.now(),
      });
      await assertSucceeds(createPostAtomically(ownerDb, 'draft-owner-no-img'));

      // Full access-call budget path: ACTIVE account_state + users profile + READY media + owner reservation + allowContact=true
      await seedAccountState(testEnv, 'student-1', 'ACTIVE');
      await seedUserProfile(testEnv, 'student-1', { allowEmailContact: true });
      await seedDoc(testEnv, 'post_reservations', 'draft-owner-full', {
        postId: 'draft-owner-full',
        ownerUid: 'student-1',
        createdAt: Timestamp.now(),
      });
      await seedMediaAsset(testEnv, 'asset-res-full', {
        ownerUid: 'student-1',
        postId: 'draft-owner-full',
        status: 'READY',
      });
      const secureUrl =
        'https://res.cloudinary.com/demo/image/upload/v1/lostlink/student-1/asset-res-full.jpg';
      await assertSucceeds(
        createPostAtomically(ownerDb, 'draft-owner-full', {
          allowContact: true,
          contactEmail: 'student1@ut.edu.vn',
          mediaAssetIds: ['asset-res-full'],
          imageUrl: secureUrl,
          imageUrls: [secureUrl],
          imagePublicId: 'lostlink/student-1/asset-res-full',
        })
      );
    });

    test('denies foreign account creating post under another owner server-reserved draft postId', async () => {
      await seedDoc(testEnv, 'post_reservations', 'reserved-draft', {
        postId: 'reserved-draft',
        ownerUid: 'student-1',
        createdAt: Timestamp.now(),
      });

      const foreignDb = studentDb(testEnv, 'student-2', 'student2@ut.edu.vn');
      await assertFails(
        createPostAtomically(foreignDb, 'reserved-draft', {
          authorId: 'student-2',
          contactEmail: 'student2@ut.edu.vn',
        })
      );
    });

    test.each([
      ['missing all fields', {}],
      ['missing postId', { ownerUid: 'student-1', createdAt: Timestamp.now() }],
      ['missing ownerUid', { postId: 'res-malformed', createdAt: Timestamp.now() }],
      ['mismatched postId', { postId: 'different-post-id', ownerUid: 'student-1', createdAt: Timestamp.now() }],
      ['null ownerUid', { postId: 'res-malformed', ownerUid: null, createdAt: Timestamp.now() }],
      ['non-string ownerUid', { postId: 'res-malformed', ownerUid: 123, createdAt: Timestamp.now() }],
      ['non-timestamp createdAt', { postId: 'res-malformed', ownerUid: 'student-1', createdAt: 'bad-ts' }],
    ])('denies create when existing reservation doc is malformed (%s)', async (_label, badReservation) => {
      await seedDoc(testEnv, 'post_reservations', 'res-malformed', badReservation);
      const ownerDb = studentDb(testEnv, 'student-1', 'student1@ut.edu.vn');
      await assertFails(createPostAtomically(ownerDb, 'res-malformed'));
    });
  });

  describe('B0-T06: Media asset ownership and attachment binding (R4)', () => {
    test('allows attaching owner READY asset bound to exact postId', async () => {
      await seedMediaAsset(testEnv, 'asset-1', {
        ownerUid: 'student-1',
        postId: 'post-media-ok',
        status: 'READY',
        cloudinaryPublicId: 'lostlink/student-1/asset-1',
        secureUrl: 'https://res.cloudinary.com/demo/image/upload/v1/lostlink/student-1/asset-1.jpg',
      });
      const db = studentDb(testEnv, 'student-1', 'student1@ut.edu.vn');
      const secureUrl =
        'https://res.cloudinary.com/demo/image/upload/v1/lostlink/student-1/asset-1.jpg';

      await assertSucceeds(
        createPostAtomically(db, 'post-media-ok', {
          mediaAssetIds: ['asset-1'],
          imageUrl: secureUrl,
          imageUrls: [secureUrl],
          imagePublicId: 'lostlink/student-1/asset-1',
        })
      );
    });

    test('denies attaching another user asset, non-READY asset, wrong postId asset, or spoofed publicId', async () => {
      await seedMediaAsset(testEnv, 'asset-other-user', {
        ownerUid: 'student-2',
        postId: 'post-media-bad-owner',
      });
      await seedMediaAsset(testEnv, 'asset-uploading', {
        ownerUid: 'student-1',
        postId: 'post-media-uploading',
        status: 'UPLOADING',
      });
      await seedMediaAsset(testEnv, 'asset-other-post', {
        ownerUid: 'student-1',
        postId: 'another-post-id',
        status: 'READY',
      });
      await seedMediaAsset(testEnv, 'asset-mine', {
        ownerUid: 'student-1',
        postId: 'post-media-spoof-pub',
        status: 'READY',
      });

      const db = studentDb(testEnv, 'student-1', 'student1@ut.edu.vn');
      const urlOther =
        'https://res.cloudinary.com/demo/image/upload/v1/lostlink/student-2/asset-other-user.jpg';
      const urlMine1 =
        'https://res.cloudinary.com/demo/image/upload/v1/lostlink/student-1/asset-uploading.jpg';
      const urlMine2 =
        'https://res.cloudinary.com/demo/image/upload/v1/lostlink/student-1/asset-other-post.jpg';
      const urlSpoof =
        'https://res.cloudinary.com/demo/image/upload/v1/lostlink/student-1/asset-mine.jpg';

      await assertFails(
        createPostAtomically(db, 'post-media-bad-owner', {
          mediaAssetIds: ['asset-other-user'],
          imageUrl: urlOther,
          imageUrls: [urlOther],
          imagePublicId: 'lostlink/student-2/asset-other-user',
        })
      );
      await assertFails(
        createPostAtomically(db, 'post-media-uploading', {
          mediaAssetIds: ['asset-uploading'],
          imageUrl: urlMine1,
          imageUrls: [urlMine1],
          imagePublicId: 'lostlink/student-1/asset-uploading',
        })
      );
      await assertFails(
        createPostAtomically(db, 'post-media-wrong-post', {
          mediaAssetIds: ['asset-other-post'],
          imageUrl: urlMine2,
          imageUrls: [urlMine2],
          imagePublicId: 'lostlink/student-1/asset-other-post',
        })
      );
      await assertFails(
        createPostAtomically(db, 'post-media-spoof-pub', {
          mediaAssetIds: ['asset-mine'],
          imageUrl: urlSpoof,
          imageUrls: [urlSpoof],
          imagePublicId: 'lostlink/student-2/victim-asset',
        })
      );
      await assertFails(
        createPostAtomically(db, 'post-no-asset-with-url', {
          mediaAssetIds: [],
          imageUrl: 'https://evil.example/img.jpg',
          imageUrls: ['https://evil.example/img.jpg'],
        })
      );
    });
  });

  describe('B0-T01 / B0-T08: Atomic update, status transitions, and HIDDEN lock', () => {
    test('allows owner atomic UPDATE and STATUS transitions (OPEN <-> RESOLVED <-> CLOSED)', async () => {
      await seedCanonicalPost(testEnv, 'post-edit', { version: 1 });
      const db = studentDb(testEnv, 'student-1', 'student1@ut.edu.vn');

      await assertSucceeds(
        updatePostAtomically(
          db,
          'post-edit',
          'student-1',
          2,
          { title: 'Balo xanh đã cập nhật', status: 'RESOLVED' },
          { changeType: 'UPDATE' }
        )
      );

      await assertSucceeds(
        updatePostAtomically(
          db,
          'post-edit',
          'student-1',
          3,
          { status: 'CLOSED' },
          { changeType: 'STATUS' }
        )
      );
    });

    test('allows atomic update removing attached image while emitting cleanup hint ref', async () => {
      await seedMediaAsset(testEnv, 'asset-old', {
        ownerUid: 'student-1',
        postId: 'post-remove-img',
        status: 'READY',
      });
      const oldUrl =
        'https://res.cloudinary.com/demo/image/upload/v1/lostlink/student-1/asset-old.jpg';
      await seedCanonicalPost(testEnv, 'post-remove-img', {
        version: 1,
        mediaAssetIds: ['asset-old'],
        imageUrl: oldUrl,
        imageUrls: [oldUrl],
        imagePublicId: 'lostlink/student-1/asset-old',
      });

      const db = studentDb(testEnv, 'student-1', 'student1@ut.edu.vn');
      await assertSucceeds(
        updatePostAtomically(
          db,
          'post-remove-img',
          'student-1',
          2,
          {
            mediaAssetIds: [],
            imageUrl: null,
            imageUrls: [],
            imagePublicId: null,
          },
          { changeType: 'UPDATE', imageAssetRefs: ['asset-old'] }
        )
      );
    });

    test('denies standalone update, version jump, content change under STATUS, immutable mutation, non-owner, and HIDDEN edit', async () => {
      await seedCanonicalPost(testEnv, 'post-v1', { version: 1 });
      await seedCanonicalPost(testEnv, 'post-hidden', {
        version: 1,
        moderationStatus: 'HIDDEN',
      });

      const ownerDb = studentDb(testEnv, 'student-1', 'student1@ut.edu.vn');
      const otherDb = studentDb(testEnv, 'student-2', 'student2@ut.edu.vn');

      // Standalone update without event
      await assertFails(
        updateDoc(doc(ownerDb, 'posts', 'post-v1'), {
          title: 'Sửa không có event',
          version: 2,
        })
      );

      // Version skip
      await assertFails(
        updatePostAtomically(ownerDb, 'post-v1', 'student-1', 3, {
          title: 'Nhảy version',
        })
      );

      // Editing title while claiming changeType STATUS
      await assertFails(
        updatePostAtomically(
          ownerDb,
          'post-v1',
          'student-1',
          2,
          { title: 'Lén sửa title trong STATUS', status: 'RESOLVED' },
          { changeType: 'STATUS' }
        )
      );

      // Mutating immutable fields (type, moderationStatus)
      await assertFails(
        updatePostAtomically(ownerDb, 'post-v1', 'student-1', 2, {
          type: 'FOUND',
        })
      );
      await assertFails(
        updatePostAtomically(ownerDb, 'post-v1', 'student-1', 2, {
          moderationStatus: 'HIDDEN',
        })
      );

      // Non-owner cannot edit
      await assertFails(
        updatePostAtomically(otherDb, 'post-v1', 'student-2', 2, {
          title: 'Người khác sửa',
        })
      );

      // Contract §3.3: Author CANNOT edit a HIDDEN post
      await assertFails(
        updatePostAtomically(ownerDb, 'post-hidden', 'student-1', 2, {
          title: 'Tự sửa bài đang bị ẩn',
        })
      );
    });
  });

  describe('B0-T03: Atomic delete, tombstone, and anti-ID-reuse', () => {
    test('denies standalone delete or delete paired with legacy _delete event suffix', async () => {
      await seedCanonicalPost(testEnv, 'post-del-bad', { version: 1 });
      const db = studentDb(testEnv, 'student-1', 'student1@ut.edu.vn');

      await assertFails(deleteDoc(doc(db, 'posts', 'post-del-bad')));

      const batch = writeBatch(db);
      batch.delete(doc(db, 'posts', 'post-del-bad'));
      batch.set(
        doc(db, 'post_events', 'post-del-bad_v2_delete'),
        makePostEventData({
          postId: 'post-del-bad',
          postVersion: 2,
          changeType: 'DELETE',
        })
      );
      batch.set(doc(db, 'post_tombstones', 'post-del-bad'), makeTombstoneData());
      await assertFails(batch.commit());
    });

    test('allows atomic delete of VISIBLE or HIDDEN own post with media ref and blocks recreating same postId', async () => {
      await seedMediaAsset(testEnv, 'asset-del', {
        ownerUid: 'student-1',
        postId: 'post-del-ok',
        status: 'READY',
      });
      const delUrl =
        'https://res.cloudinary.com/demo/image/upload/v1/lostlink/student-1/asset-del.jpg';
      await seedCanonicalPost(testEnv, 'post-del-ok', {
        version: 2,
        moderationStatus: 'HIDDEN',
        mediaAssetIds: ['asset-del'],
        imageUrl: delUrl,
        imageUrls: [delUrl],
        imagePublicId: 'lostlink/student-1/asset-del',
      });
      const db = studentDb(testEnv, 'student-1', 'student1@ut.edu.vn');
      const otherDb = studentDb(testEnv, 'student-2', 'student2@ut.edu.vn');

      // Non-owner cannot delete
      await assertFails(
        deletePostAtomically(otherDb, 'post-del-ok', 'student-2', 3, {
          imageAssetRefs: ['asset-del'],
        })
      );

      // Owner can atomically delete even HIDDEN post (Contract §3.3)
      await assertSucceeds(
        deletePostAtomically(db, 'post-del-ok', 'student-1', 3, {
          imageAssetRefs: ['asset-del'],
        })
      );

      // Re-creating same postId is denied by post_tombstones existence (B0-T03)
      await assertFails(createPostAtomically(db, 'post-del-ok'));
    });
  });

  describe('B0-T08 & Contract §6: Visibility queries and restored VISIBLE privacy', () => {
    test('enforces HIDDEN isolation on get/list and validates Home & My Posts queries', async () => {
      await seedCanonicalPost(testEnv, 'p-vis-open', {
        authorId: 'student-1',
        type: 'LOST',
        moderationStatus: 'VISIBLE',
        status: 'OPEN',
      });
      await seedCanonicalPost(testEnv, 'p-vis-resolved', {
        authorId: 'student-1',
        type: 'LOST',
        moderationStatus: 'VISIBLE',
        status: 'RESOLVED',
      });
      await seedCanonicalPost(testEnv, 'p-vis-found', {
        authorId: 'student-1',
        type: 'FOUND',
        moderationStatus: 'VISIBLE',
        status: 'OPEN',
      });
      await seedCanonicalPost(testEnv, 'p-vis-closed', {
        authorId: 'student-1',
        moderationStatus: 'VISIBLE',
        status: 'CLOSED',
      });
      await seedCanonicalPost(testEnv, 'p-hidden', {
        authorId: 'student-1',
        moderationStatus: 'HIDDEN',
        status: 'OPEN',
      });

      const ownerDb = studentDb(testEnv, 'student-1', 'student1@ut.edu.vn');
      const otherDb = studentDb(testEnv, 'student-2', 'student2@ut.edu.vn');

      // Single-doc HIDDEN read: owner allowed, non-owner denied
      await assertSucceeds(getDoc(doc(ownerDb, 'posts', 'p-hidden')));
      await assertFails(getDoc(doc(otherDb, 'posts', 'p-hidden')));

      // Unfiltered query rejected by Rules
      await assertFails(getDocs(query(collection(otherDb, 'posts'), limit(10))));

      // Home Feed query matches Rules and excludes HIDDEN + CLOSED
      const homeSnap = await assertSucceeds(
        getDocs(
          query(
            collection(otherDb, 'posts'),
            where('moderationStatus', '==', 'VISIBLE'),
            where('status', 'in', ['OPEN', 'RESOLVED']),
            orderBy('createdAt', 'desc'),
            orderBy('__name__', 'desc'),
            limit(20)
          )
        )
      );
      expect(homeSnap.docs.map((d) => d.id).sort()).toEqual([
        'p-vis-found',
        'p-vis-open',
        'p-vis-resolved',
      ]);

      // Home Feed type-filtered query (Index #2)
      const lostSnap = await assertSucceeds(
        getDocs(
          query(
            collection(otherDb, 'posts'),
            where('moderationStatus', '==', 'VISIBLE'),
            where('status', 'in', ['OPEN', 'RESOLVED']),
            where('type', '==', 'LOST'),
            orderBy('createdAt', 'desc'),
            orderBy('__name__', 'desc'),
            limit(20)
          )
        )
      );
      expect(lostSnap.docs.map((d) => d.id).sort()).toEqual([
        'p-vis-open',
        'p-vis-resolved',
      ]);

      // My Posts query by owner includes HIDDEN and CLOSED; other student querying authorId=student-1 fails
      const mySnap = await assertSucceeds(
        getDocs(
          query(
            collection(ownerDb, 'posts'),
            where('authorId', '==', 'student-1'),
            orderBy('createdAt', 'desc'),
            orderBy('__name__', 'desc'),
            limit(20)
          )
        )
      );
      expect(mySnap.size).toBe(5);
      await assertFails(
        getDocs(
          query(
            collection(otherDb, 'posts'),
            where('authorId', '==', 'student-1'),
            limit(20)
          )
        )
      );
    });

    test('Restored VISIBLE Privacy Check (§6): non-owner sees zero reason on restored post', async () => {
      await seedCanonicalPost(testEnv, 'post-restored', {
        authorId: 'student-1',
        moderationStatus: 'VISIBLE',
        status: 'OPEN',
      });
      await seedDoc(testEnv, 'post_moderations', 'post-restored', {
        postId: 'post-restored',
        authorId: 'student-1',
        moderationStatus: 'VISIBLE',
        reason: null,
        updatedAt: Timestamp.now(),
      });
      await seedDoc(testEnv, 'admin_moderation_notes', 'post-restored', {
        postId: 'post-restored',
        note: 'Internal admin note',
        updatedBy: 'admin-1',
        updatedAt: Timestamp.now(),
      });

      const otherDb = studentDb(testEnv, 'student-2', 'student2@ut.edu.vn');
      const postSnap = await assertSucceeds(
        getDoc(doc(otherDb, 'posts', 'post-restored'))
      );
      expect(postSnap.data().moderationReason).toBeUndefined();
      await assertFails(getDoc(doc(otherDb, 'post_moderations', 'post-restored')));
      await assertFails(
        getDoc(doc(otherDb, 'admin_moderation_notes', 'post-restored'))
      );
    });
  });

  describe('C4-R1 amendment: get-missing and Android reader-before-create protocol', () => {
    test('verified student gets exists=false on absent posts and post_tombstones while list and non-verified remain denied', async () => {
      const ownerDb = studentDb(testEnv, 'student-1', 'student1@ut.edu.vn');
      const anon = unauthDb(testEnv);
      const unverified = rawAuthedDb(testEnv, 'u-unv', {
        email: 'student1@ut.edu.vn',
        email_verified: false,
      });
      const nonStudent = rawAuthedDb(testEnv, 'u-gmail', {
        email: 'student1@gmail.com',
        email_verified: true,
      });

      const absentPostSnap = await assertSucceeds(
        getDoc(doc(ownerDb, 'posts', 'missing-post-c4'))
      );
      expect(absentPostSnap.exists()).toBe(false);

      const absentTombSnap = await assertSucceeds(
        getDoc(doc(ownerDb, 'post_tombstones', 'missing-post-c4'))
      );
      expect(absentTombSnap.exists()).toBe(false);

      // List queries are unaffected and remain denied when unfiltered
      await assertFails(getDocs(query(collection(ownerDb, 'posts'), limit(5))));
      await assertFails(
        getDocs(query(collection(ownerDb, 'post_tombstones'), limit(5)))
      );

      // Unauthenticated, unverified, and non-@ut.edu.vn accounts are denied get-missing
      await assertFails(getDoc(doc(anon, 'posts', 'missing-post-c4')));
      await assertFails(getDoc(doc(anon, 'post_tombstones', 'missing-post-c4')));
      await assertFails(getDoc(doc(unverified, 'posts', 'missing-post-c4')));
      await assertFails(
        getDoc(doc(unverified, 'post_tombstones', 'missing-post-c4'))
      );
      await assertFails(getDoc(doc(nonStudent, 'posts', 'missing-post-c4')));
      await assertFails(
        getDoc(doc(nonStudent, 'post_tombstones', 'missing-post-c4'))
      );
    });

    test('existing HIDDEN posts and foreign post_tombstones remain unreadable to non-owners', async () => {
      await seedCanonicalPost(testEnv, 'hidden-c4', {
        authorId: 'student-1',
        moderationStatus: 'HIDDEN',
      });
      await seedDoc(testEnv, 'post_tombstones', 'tomb-c4', {
        authorId: 'student-1',
        deletedAt: Timestamp.now(),
      });

      const ownerDb = studentDb(testEnv, 'student-1', 'student1@ut.edu.vn');
      const otherDb = studentDb(testEnv, 'student-2', 'student2@ut.edu.vn');

      const ownerHidden = await assertSucceeds(
        getDoc(doc(ownerDb, 'posts', 'hidden-c4'))
      );
      expect(ownerHidden.exists()).toBe(true);
      const ownerTomb = await assertSucceeds(
        getDoc(doc(ownerDb, 'post_tombstones', 'tomb-c4'))
      );
      expect(ownerTomb.exists()).toBe(true);

      await assertFails(getDoc(doc(otherDb, 'posts', 'hidden-c4')));
      await assertFails(getDoc(doc(otherDb, 'post_tombstones', 'tomb-c4')));
    });

    test('Android create protocol (postExists + transaction get post -> tombstone -> profile -> set post+event) succeeds', async () => {
      const db = studentDb(testEnv, 'student-1', 'student1@ut.edu.vn');

      // Case 1: Brand-new account without users/{uid} doc, reservation, or image
      const draftId1 = 'android-proto-no-profile';
      const precheck1 = await assertSucceeds(getDoc(doc(db, 'posts', draftId1)));
      expect(precheck1.exists()).toBe(false);

      await assertSucceeds(
        runTransaction(db, async (tx) => {
          const postSnap = await tx.get(doc(db, 'posts', draftId1));
          const tombSnap = await tx.get(doc(db, 'post_tombstones', draftId1));
          const profileSnap = await tx.get(doc(db, 'users', 'student-1'));
          expect(postSnap.exists()).toBe(false);
          expect(tombSnap.exists()).toBe(false);
          expect(profileSnap.exists()).toBe(false);

          tx.set(doc(db, 'posts', draftId1), makePostData({ authorId: 'student-1' }));
          tx.set(
            doc(db, 'post_events', `${draftId1}_v1`),
            makePostEventData({
              postId: draftId1,
              authorId: 'student-1',
              postVersion: 1,
              changeType: 'CREATE',
            })
          );
        })
      );

      // Case 2: With users profile, owned post_reservations, and READY media_assets
      const draftId2 = 'android-proto-full-media';
      await seedUserProfile(testEnv, 'student-1', {
        displayName: 'Nguyễn Văn A',
        allowEmailContact: true,
      });
      await seedDoc(testEnv, 'post_reservations', draftId2, {
        postId: draftId2,
        ownerUid: 'student-1',
        createdAt: Timestamp.now(),
      });
      await seedMediaAsset(testEnv, 'asset-c4-1', {
        ownerUid: 'student-1',
        postId: draftId2,
        status: 'READY',
        cloudinaryPublicId: 'lostlink/student-1/asset-c4-1',
        secureUrl:
          'https://res.cloudinary.com/demo/image/upload/v1/lostlink/student-1/asset-c4-1.jpg',
      });

      const precheck2 = await assertSucceeds(getDoc(doc(db, 'posts', draftId2)));
      expect(precheck2.exists()).toBe(false);

      const assetUrl =
        'https://res.cloudinary.com/demo/image/upload/v1/lostlink/student-1/asset-c4-1.jpg';
      await assertSucceeds(
        runTransaction(db, async (tx) => {
          const postSnap = await tx.get(doc(db, 'posts', draftId2));
          const tombSnap = await tx.get(doc(db, 'post_tombstones', draftId2));
          const profileSnap = await tx.get(doc(db, 'users', 'student-1'));
          expect(postSnap.exists()).toBe(false);
          expect(tombSnap.exists()).toBe(false);
          expect(profileSnap.exists()).toBe(true);

          tx.set(
            doc(db, 'posts', draftId2),
            makePostData({
              authorId: 'student-1',
              mediaAssetIds: ['asset-c4-1'],
              imageUrl: assetUrl,
              imageUrls: [assetUrl],
              imagePublicId: 'lostlink/student-1/asset-c4-1',
            })
          );
          tx.set(
            doc(db, 'post_events', `${draftId2}_v1`),
            makePostEventData({
              postId: draftId2,
              authorId: 'student-1',
              postVersion: 1,
              changeType: 'CREATE',
              imageAssetRefs: ['asset-c4-1'],
            })
          );
        })
      );
    });

    test('Android reader-before-create blocks reuse of deleted postId (own or foreign tombstone)', async () => {
      await seedDoc(testEnv, 'post_tombstones', 'tomb-own', {
        authorId: 'student-1',
        deletedAt: Timestamp.now(),
      });
      await seedDoc(testEnv, 'post_tombstones', 'tomb-other', {
        authorId: 'student-2',
        deletedAt: Timestamp.now(),
      });

      const db1 = studentDb(testEnv, 'student-1', 'student1@ut.edu.vn');

      // Foreign tombstone cannot be read and cannot be overwritten by post create
      await assertFails(
        runTransaction(db1, async (tx) => {
          await tx.get(doc(db1, 'posts', 'tomb-other'));
          await tx.get(doc(db1, 'post_tombstones', 'tomb-other'));
        })
      );

      // Own tombstone is detected as exists=true and create write is still rejected by Rules
      await assertFails(
        runTransaction(db1, async (tx) => {
          const postSnap = await tx.get(doc(db1, 'posts', 'tomb-own'));
          const tombSnap = await tx.get(doc(db1, 'post_tombstones', 'tomb-own'));
          expect(postSnap.exists()).toBe(false);
          expect(tombSnap.exists()).toBe(true);
          tx.set(doc(db1, 'posts', 'tomb-own'), makePostData({ authorId: 'student-1' }));
          tx.set(
            doc(db1, 'post_events', 'tomb-own_v1'),
            makePostEventData({
              postId: 'tomb-own',
              authorId: 'student-1',
              postVersion: 1,
              changeType: 'CREATE',
            })
          );
        })
      );

      // C3-R1 reservation lock stays enforced even though get-missing returns exists=false
      await seedDoc(testEnv, 'post_reservations', 'reserved-foreign-c4', {
        postId: 'reserved-foreign-c4',
        ownerUid: 'student-2',
        createdAt: Timestamp.now(),
      });
      await assertFails(
        runTransaction(db1, async (tx) => {
          const postSnap = await tx.get(doc(db1, 'posts', 'reserved-foreign-c4'));
          const tombSnap = await tx.get(doc(db1, 'post_tombstones', 'reserved-foreign-c4'));
          expect(postSnap.exists()).toBe(false);
          expect(tombSnap.exists()).toBe(false);
          tx.set(
            doc(db1, 'posts', 'reserved-foreign-c4'),
            makePostData({ authorId: 'student-1' })
          );
          tx.set(
            doc(db1, 'post_events', 'reserved-foreign-c4_v1'),
            makePostEventData({
              postId: 'reserved-foreign-c4',
              authorId: 'student-1',
              postVersion: 1,
              changeType: 'CREATE',
            })
          );
        })
      );
    });
  });
});
