const {
  Timestamp,
  assertFails,
  assertSucceeds,
  deleteDoc,
  doc,
  getDoc,
  makeInitialJobMap,
  makePostData,
  makePostEventData,
  seedCanonicalPost,
  seedDoc,
  seedMediaAsset,
  setupRulesTestEnv,
  studentDb,
  updateDoc,
  writeBatch,
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

describe('post_events security rules (B0-T02, Contract §3.4)', () => {
  test('denies client read, update, and delete on post_events', async () => {
    await seedDoc(testEnv, 'post_events', 'p1_v1', {
      ...makePostEventData({ postId: 'p1' }),
      createdAt: Timestamp.now(),
    });
    const db = studentDb(testEnv, 'student-1', 'student1@ut.edu.vn');

    await assertFails(getDoc(doc(db, 'post_events', 'p1_v1')));
    await assertFails(
      updateDoc(doc(db, 'post_events', 'p1_v1'), {
        'searchJob.status': 'DONE',
      })
    );
    await assertFails(deleteDoc(doc(db, 'post_events', 'p1_v1')));
  });

  test('denies standalone event create without matching atomic post operation', async () => {
    await seedCanonicalPost(testEnv, 'p-existing', { authorId: 'student-1' });
    const db = studentDb(testEnv, 'student-1', 'student1@ut.edu.vn');

    const { setDoc } = require('firebase/firestore');
    await assertFails(
      setDoc(
        doc(db, 'post_events', 'p-orphan_v1'),
        makePostEventData({ postId: 'p-orphan' })
      )
    );
    await assertFails(
      setDoc(
        doc(db, 'post_events', 'p-existing_v2'),
        makePostEventData({
          postId: 'p-existing',
          postVersion: 2,
          changeType: 'UPDATE',
        })
      )
    );
  });

  test('denies top-level status, missing jobs, DONE/PROCESSING jobs, attempts!=0, and non-null lock fields', async () => {
    const db = studentDb(testEnv, 'student-1', 'student1@ut.edu.vn');

    const tryCreateWithBadEvent = async (postId, eventDocId, eventPayload) => {
      const batch = writeBatch(db);
      batch.set(doc(db, 'posts', postId), makePostData());
      batch.set(doc(db, 'post_events', eventDocId), eventPayload);
      await assertFails(batch.commit());
    };

    // Legacy root status forbidden
    await tryCreateWithBadEvent('p-root-status', 'p-root-status_v1', {
      ...makePostEventData({ postId: 'p-root-status' }),
      status: 'PENDING',
    });

    // Missing mediaCleanupJob
    const missingJob = makePostEventData({ postId: 'p-miss-job' });
    delete missingJob.mediaCleanupJob;
    await tryCreateWithBadEvent('p-miss-job', 'p-miss-job_v1', missingJob);

    // Job already marked DONE / PROCESSING / attempts > 0 / lockOwner set
    await tryCreateWithBadEvent(
      'p-job-done',
      'p-job-done_v1',
      makePostEventData({
        postId: 'p-job-done',
        searchJob: makeInitialJobMap({ status: 'DONE' }),
      })
    );
    await tryCreateWithBadEvent(
      'p-job-proc',
      'p-job-proc_v1',
      makePostEventData({
        postId: 'p-job-proc',
        notifyJob: makeInitialJobMap({ status: 'PROCESSING' }),
      })
    );
    await tryCreateWithBadEvent(
      'p-job-attempts',
      'p-job-attempts_v1',
      makePostEventData({
        postId: 'p-job-attempts',
        mediaCleanupJob: makeInitialJobMap({ attempts: 1 }),
      })
    );
    await tryCreateWithBadEvent(
      'p-job-claim',
      'p-job-claim_v1',
      makePostEventData({
        postId: 'p-job-claim',
        searchJob: makeInitialJobMap({ claimToken: 'forged-token' }),
      })
    );
  });

  test('denies forged authorId, server-only changeTypes, bad eventId format, and foreign imageAssetRefs', async () => {
    const db = studentDb(testEnv, 'student-1', 'student1@ut.edu.vn');
    await seedMediaAsset(testEnv, 'asset-foreign', {
      ownerUid: 'student-2',
      postId: 'p-foreign',
    });

    const tryBadEvent = async (postId, eventDocId, overrides) => {
      const batch = writeBatch(db);
      batch.set(doc(db, 'posts', postId), makePostData());
      batch.set(
        doc(db, 'post_events', eventDocId),
        makePostEventData({ postId, ...overrides })
      );
      await assertFails(batch.commit());
    };

    await tryBadEvent('p-forged-author', 'p-forged-author_v1', {
      authorId: 'student-2',
    });
    await tryBadEvent('p-mod-type', 'p-mod-type_v1', {
      changeType: 'MODERATION',
    });
    await tryBadEvent('p-backfill-type', 'p-backfill-type_v1', {
      changeType: 'BACKFILL',
    });
    await tryBadEvent('p-bad-id', 'p-bad-id_v1_extra', {});
    await tryBadEvent('p-foreign-ref', 'p-foreign-ref_v1', {
      imageAssetRefs: ['asset-foreign'],
    });
  });

  test('allows valid PersistedPostEvent inside atomic create and verifies stored fields', async () => {
    const db = studentDb(testEnv, 'student-1', 'student1@ut.edu.vn');
    const batch = writeBatch(db);
    batch.set(doc(db, 'posts', 'p-ev-ok'), makePostData());
    batch.set(
      doc(db, 'post_events', 'p-ev-ok_v1'),
      makePostEventData({ postId: 'p-ev-ok' })
    );
    await assertSucceeds(batch.commit());

    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      const snap = await getDoc(doc(ctx.firestore(), 'post_events', 'p-ev-ok_v1'));
      expect(snap.exists()).toBe(true);
      expect(snap.data().status).toBeUndefined();
      expect(snap.data().searchJob.status).toBe('PENDING');
      expect(snap.data().notifyJob.status).toBe('PENDING');
      expect(snap.data().mediaCleanupJob.status).toBe('PENDING');
    });
  });
});
