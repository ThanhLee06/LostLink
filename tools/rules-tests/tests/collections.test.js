const {
  Timestamp,
  assertFails,
  assertSucceeds,
  deleteDoc,
  doc,
  getDoc,
  makeTombstoneData,
  seedDoc,
  seedMediaAsset,
  setDoc,
  setupRulesTestEnv,
  studentDb,
  updateDoc,
} = require('./helpers');

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

describe('Remaining B0 collections & catch-all rules (Contract §3.2, §3.4–§3.6)', () => {
  test('post_moderations: author-only read, no client writes', async () => {
    await seedDoc(testEnv, 'post_moderations', 'p1', {
      postId: 'p1',
      authorId: 'student-1',
      moderationStatus: 'HIDDEN',
      reason: 'Thông tin không phù hợp',
      updatedAt: Timestamp.now(),
    });
    const authorDb = studentDb(testEnv, 'student-1', 'student1@ut.edu.vn');
    const otherDb = studentDb(testEnv, 'student-2', 'student2@ut.edu.vn');

    const snap = await assertSucceeds(getDoc(doc(authorDb, 'post_moderations', 'p1')));
    expect(snap.data().reason).toBe('Thông tin không phù hợp');
    await assertFails(getDoc(doc(otherDb, 'post_moderations', 'p1')));
    await assertFails(
      updateDoc(doc(authorDb, 'post_moderations', 'p1'), { moderationStatus: 'VISIBLE' })
    );
    await assertFails(deleteDoc(doc(authorDb, 'post_moderations', 'p1')));
  });

  test('admin_moderation_notes & post_embeddings: deny all client reads and writes', async () => {
    await seedDoc(testEnv, 'admin_moderation_notes', 'p1', {
      postId: 'p1',
      note: 'Internal evidence note',
      updatedBy: 'admin-1',
      updatedAt: Timestamp.now(),
    });
    await seedDoc(testEnv, 'post_embeddings', 'p1', {
      postId: 'p1',
      authorId: 'student-1',
      model: 'gemini-embedding-001',
    });
    const authorDb = studentDb(testEnv, 'student-1', 'student1@ut.edu.vn');

    await assertFails(getDoc(doc(authorDb, 'admin_moderation_notes', 'p1')));
    await assertFails(
      setDoc(doc(authorDb, 'admin_moderation_notes', 'p2'), { note: 'fake' })
    );
    await assertFails(getDoc(doc(authorDb, 'post_embeddings', 'p1')));
    await assertFails(
      setDoc(doc(authorDb, 'post_embeddings', 'p2'), { postId: 'p2' })
    );
  });

  test('post_reservations & media_assets (B0-T06): owner read only, client writes denied', async () => {
    await seedDoc(testEnv, 'post_reservations', 'draft-1', {
      postId: 'draft-1',
      ownerUid: 'student-1',
      createdAt: Timestamp.now(),
    });
    await seedMediaAsset(testEnv, 'asset-1', {
      ownerUid: 'student-1',
      postId: 'draft-1',
    });

    const ownerDb = studentDb(testEnv, 'student-1', 'student1@ut.edu.vn');
    const otherDb = studentDb(testEnv, 'student-2', 'student2@ut.edu.vn');

    await assertSucceeds(getDoc(doc(ownerDb, 'post_reservations', 'draft-1')));
    await assertFails(getDoc(doc(otherDb, 'post_reservations', 'draft-1')));
    await assertFails(
      setDoc(doc(ownerDb, 'post_reservations', 'draft-2'), {
        postId: 'draft-2',
        ownerUid: 'student-1',
      })
    );

    await assertSucceeds(getDoc(doc(ownerDb, 'media_assets', 'asset-1')));
    await assertFails(getDoc(doc(otherDb, 'media_assets', 'asset-1')));
    await assertFails(
      updateDoc(doc(ownerDb, 'media_assets', 'asset-1'), { status: 'DELETED' })
    );
    await assertFails(deleteDoc(doc(otherDb, 'media_assets', 'asset-1')));
  });

  test('post_tombstones: owner-only read, standalone write denied', async () => {
    await seedDoc(testEnv, 'post_tombstones', 'deleted-1', {
      authorId: 'student-1',
      deletedAt: Timestamp.now(),
    });
    const ownerDb = studentDb(testEnv, 'student-1', 'student1@ut.edu.vn');
    const otherDb = studentDb(testEnv, 'student-2', 'student2@ut.edu.vn');

    await assertSucceeds(getDoc(doc(ownerDb, 'post_tombstones', 'deleted-1')));
    await assertFails(getDoc(doc(otherDb, 'post_tombstones', 'deleted-1')));
    await assertFails(
      setDoc(doc(ownerDb, 'post_tombstones', 'fake-tomb'), makeTombstoneData())
    );
    await assertFails(deleteDoc(doc(ownerDb, 'post_tombstones', 'deleted-1')));
  });

  test('users, saved_posts, and unknown collections follow strict isolation and catch-all deny', async () => {
    const db1 = studentDb(testEnv, 'student-1', 'student1@ut.edu.vn');
    const db2 = studentDb(testEnv, 'student-2', 'student2@ut.edu.vn');

    // ACTIVE owner can manage users/{uid} and saved_posts/{uid}/items/{postId}
    await assertSucceeds(
      setDoc(doc(db1, 'users', 'student-1'), {
        uid: 'student-1',
        allowEmailContact: true,
      })
    );
    await assertSucceeds(
      setDoc(doc(db1, 'saved_posts', 'student-1', 'items', 'p-1'), {
        postId: 'p-1',
      })
    );

    // Other student blocked
    await assertFails(getDoc(doc(db2, 'users', 'student-1')));
    await assertFails(getDoc(doc(db2, 'saved_posts', 'student-1', 'items', 'p-1')));
    await assertFails(
      setDoc(doc(db2, 'saved_posts', 'student-1', 'items', 'p-2'), { postId: 'p-2' })
    );

    // Catch-all deny
    await assertFails(getDoc(doc(db1, 'unknown_collection', 'doc-1')));
    await assertFails(
      setDoc(doc(db1, 'unknown_collection', 'doc-1'), { foo: 'bar' })
    );
  });
});
