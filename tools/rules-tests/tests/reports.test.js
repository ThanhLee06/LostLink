const {
  Timestamp,
  assertFails,
  assertSucceeds,
  deleteDoc,
  doc,
  getDoc,
  makeReportData,
  seedCanonicalPost,
  seedDoc,
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

describe('reports security rules (B0-T15, Contract §3.8)', () => {
  test('allows verified student to report another user VISIBLE post once and read own report', async () => {
    await seedCanonicalPost(testEnv, 'post-1', {
      authorId: 'student-1',
      moderationStatus: 'VISIBLE',
    });
    const reporterDb = studentDb(testEnv, 'student-2', 'student2@ut.edu.vn');

    await assertSucceeds(
      setDoc(
        doc(reporterDb, 'reports', 'post-1_student-2'),
        makeReportData({ postId: 'post-1', reporterUid: 'student-2' })
      )
    );

    const ownSnap = await assertSucceeds(
      getDoc(doc(reporterDb, 'reports', 'post-1_student-2'))
    );
    expect(ownSnap.exists()).toBe(true);

    // Duplicate .set() hits update rule and is rejected
    await assertFails(
      setDoc(
        doc(reporterDb, 'reports', 'post-1_student-2'),
        makeReportData({ postId: 'post-1', reporterUid: 'student-2' })
      )
    );
  });

  test('denies self-report, non-existent post, and HIDDEN post', async () => {
    await seedCanonicalPost(testEnv, 'post-own', {
      authorId: 'student-1',
      moderationStatus: 'VISIBLE',
    });
    await seedCanonicalPost(testEnv, 'post-hidden', {
      authorId: 'student-1',
      moderationStatus: 'HIDDEN',
    });

    const ownerDb = studentDb(testEnv, 'student-1', 'student1@ut.edu.vn');
    const reporterDb = studentDb(testEnv, 'student-2', 'student2@ut.edu.vn');

    // Self-report
    await assertFails(
      setDoc(
        doc(ownerDb, 'reports', 'post-own_student-1'),
        makeReportData({ postId: 'post-own', reporterUid: 'student-1' })
      )
    );
    // Missing post (Contract §3.8 requires post exists and VISIBLE)
    await assertFails(
      setDoc(
        doc(reporterDb, 'reports', 'post-missing_student-2'),
        makeReportData({ postId: 'post-missing', reporterUid: 'student-2' })
      )
    );
    // HIDDEN post
    await assertFails(
      setDoc(
        doc(reporterDb, 'reports', 'post-hidden_student-2'),
        makeReportData({ postId: 'post-hidden', reporterUid: 'student-2' })
      )
    );
  });

  test('denies spoofed reporterUid, processed status, publicNote, bad reason/ID/timestamp, and overlong description', async () => {
    await seedCanonicalPost(testEnv, 'post-1', {
      authorId: 'student-1',
      moderationStatus: 'VISIBLE',
    });
    const reporterDb = studentDb(testEnv, 'student-2', 'student2@ut.edu.vn');

    // Forged reporterUid
    await assertFails(
      setDoc(
        doc(reporterDb, 'reports', 'post-1_student-3'),
        makeReportData({ postId: 'post-1', reporterUid: 'student-3' })
      )
    );
    // Non-PENDING status
    await assertFails(
      setDoc(
        doc(reporterDb, 'reports', 'post-1_student-2'),
        makeReportData({ status: 'RESOLVED_HIDDEN' })
      )
    );
    // Admin-only publicNote
    await assertFails(
      setDoc(
        doc(reporterDb, 'reports', 'post-1_student-2'),
        makeReportData({ publicNote: 'Approved by fake admin' })
      )
    );
    // Invalid reason
    await assertFails(
      setDoc(
        doc(reporterDb, 'reports', 'post-1_student-2'),
        makeReportData({ reason: 'HATE' })
      )
    );
    // Wrong doc ID format
    await assertFails(
      setDoc(doc(reporterDb, 'reports', 'random-id'), makeReportData())
    );
    // Fake backdated createdAt
    await assertFails(
      setDoc(
        doc(reporterDb, 'reports', 'post-1_student-2'),
        makeReportData({ createdAt: Timestamp.fromMillis(1700000000000) })
      )
    );
    // Description > 500 chars
    await assertFails(
      setDoc(
        doc(reporterDb, 'reports', 'post-1_student-2'),
        makeReportData({ description: 'x'.repeat(501) })
      )
    );
  });

  test('denies non-reporter read and all client update/delete operations', async () => {
    await seedDoc(testEnv, 'reports', 'post-1_student-2', {
      ...makeReportData({ postId: 'post-1', reporterUid: 'student-2' }),
      createdAt: Timestamp.now(),
    });
    const reporterDb = studentDb(testEnv, 'student-2', 'student2@ut.edu.vn');
    const authorDb = studentDb(testEnv, 'student-1', 'student1@ut.edu.vn');

    await assertFails(getDoc(doc(authorDb, 'reports', 'post-1_student-2')));
    await assertFails(
      updateDoc(doc(reporterDb, 'reports', 'post-1_student-2'), {
        description: 'Edited reason',
      })
    );
    await assertFails(deleteDoc(doc(reporterDb, 'reports', 'post-1_student-2')));
  });
});
