const {
  Timestamp,
  assertFails,
  assertSucceeds,
  deleteDoc,
  doc,
  getDoc,
  makeReportData,
  rawAuthedDb,
  seedAccountState,
  seedCanonicalPost,
  seedDoc,
  setDoc,
  setupRulesTestEnv,
  studentDb,
  unauthDb,
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

describe('reports security rules (B0-T15, ST48, Contract §3.8)', () => {
  test('allows verified student to report another user VISIBLE post once and read own report', async () => {
    await seedCanonicalPost(testEnv, 'post-1', {
      authorId: 'student-1',
      moderationStatus: 'VISIBLE',
    });
    const reporterDb = studentDb(testEnv, 'student-2', 'student2@ut.edu.vn');

    // Preflight get on own deterministic reportId succeeds with exists() == false before creation
    const beforeSnap = await assertSucceeds(
      getDoc(doc(reporterDb, 'reports', 'post-1_student-2'))
    );
    expect(beforeSnap.exists()).toBe(false);

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

    // Duplicate .set() on fixed ${postId}_${reporterUid} hits update rule and is rejected
    await assertFails(
      setDoc(
        doc(reporterDb, 'reports', 'post-1_student-2'),
        makeReportData({ postId: 'post-1', reporterUid: 'student-2' })
      )
    );
  });

  test('ST48: enforces verified @ut.edu.vn identity and blocks DELETING/DELETED accounts', async () => {
    await seedCanonicalPost(testEnv, 'post-1', {
      authorId: 'student-1',
      moderationStatus: 'VISIBLE',
    });

    const anonDb = unauthDb(testEnv);
    const unverifiedDb = rawAuthedDb(testEnv, 'student-unv', {
      email: 'unv@ut.edu.vn',
      email_verified: false,
    });
    const externalEmailDb = rawAuthedDb(testEnv, 'student-ext', {
      email: 'user@gmail.com',
      email_verified: true,
    });

    // Unauthenticated
    await assertFails(
      setDoc(
        doc(anonDb, 'reports', 'post-1_anon'),
        makeReportData({ postId: 'post-1', reporterUid: 'anon' })
      )
    );
    // Unverified @ut.edu.vn
    await assertFails(
      setDoc(
        doc(unverifiedDb, 'reports', 'post-1_student-unv'),
        makeReportData({ postId: 'post-1', reporterUid: 'student-unv' })
      )
    );
    // Non-@ut.edu.vn domain
    await assertFails(
      setDoc(
        doc(externalEmailDb, 'reports', 'post-1_student-ext'),
        makeReportData({ postId: 'post-1', reporterUid: 'student-ext' })
      )
    );

    // Account in DELETING or DELETED state
    await seedAccountState(testEnv, 'student-del', 'DELETING');
    const deletingDb = studentDb(testEnv, 'student-del', 'del@ut.edu.vn');
    await assertFails(
      setDoc(
        doc(deletingDb, 'reports', 'post-1_student-del'),
        makeReportData({ postId: 'post-1', reporterUid: 'student-del' })
      )
    );
  });

  test('denies self-report, non-existent post, tombstoned post, and HIDDEN post', async () => {
    await seedCanonicalPost(testEnv, 'post-own', {
      authorId: 'student-1',
      moderationStatus: 'VISIBLE',
    });
    await seedCanonicalPost(testEnv, 'post-hidden', {
      authorId: 'student-1',
      moderationStatus: 'HIDDEN',
    });
    await seedCanonicalPost(testEnv, 'post-tombstoned', {
      authorId: 'student-1',
      moderationStatus: 'VISIBLE',
    });
    await seedDoc(testEnv, 'post_tombstones', 'post-tombstoned', {
      authorId: 'student-1',
      deletedAt: Timestamp.now(),
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
    // Tombstoned post
    await assertFails(
      setDoc(
        doc(reporterDb, 'reports', 'post-tombstoned_student-2'),
        makeReportData({ postId: 'post-tombstoned', reporterUid: 'student-2' })
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
    // Mismatched reportId vs postId_reporterUid
    await assertFails(
      setDoc(
        doc(reporterDb, 'reports', 'post-2_student-2'),
        makeReportData({ postId: 'post-1', reporterUid: 'student-2' })
      )
    );
    // Non-PENDING status
    await assertFails(
      setDoc(
        doc(reporterDb, 'reports', 'post-1_student-2'),
        makeReportData({ status: 'RESOLVED_HIDDEN' })
      )
    );
    await assertFails(
      setDoc(
        doc(reporterDb, 'reports', 'post-1_student-2'),
        makeReportData({ status: 'REJECTED' })
      )
    );
    // Admin-only publicNote
    await assertFails(
      setDoc(
        doc(reporterDb, 'reports', 'post-1_student-2'),
        makeReportData({ publicNote: 'Approved by fake admin' })
      )
    );
    // Extra unknown field
    await assertFails(
      setDoc(
        doc(reporterDb, 'reports', 'post-1_student-2'),
        makeReportData({ extraField: 'not-allowed' })
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

  test('ST48: denies non-reporter read and all client update/delete/overwrite on PENDING or Admin-processed reports', async () => {
    await seedCanonicalPost(testEnv, 'post-1', {
      authorId: 'student-1',
      moderationStatus: 'VISIBLE',
    });
    // Simulate a report already processed by Admin (status = REJECTED, with publicNote)
    await seedDoc(testEnv, 'reports', 'post-1_student-2', {
      ...makeReportData({
        postId: 'post-1',
        reporterUid: 'student-2',
        status: 'REJECTED',
        publicNote: 'Bài viết hợp lệ sau khi Admin kiểm tra.',
      }),
      createdAt: Timestamp.now(),
    });
    const reporterDb = studentDb(testEnv, 'student-2', 'student2@ut.edu.vn');
    const authorDb = studentDb(testEnv, 'student-1', 'student1@ut.edu.vn');

    // Non-reporter cannot read existing or absent foreign report
    await assertFails(getDoc(doc(authorDb, 'reports', 'post-1_student-2')));
    await assertFails(getDoc(doc(authorDb, 'reports', 'post-1_student-3')));

    // Reporter can read their own processed report
    const ownSnap = await assertSucceeds(
      getDoc(doc(reporterDb, 'reports', 'post-1_student-2'))
    );
    expect(ownSnap.data().status).toBe('REJECTED');

    // Client cannot overwrite an Admin-processed report via setDoc
    await assertFails(
      setDoc(
        doc(reporterDb, 'reports', 'post-1_student-2'),
        makeReportData({ postId: 'post-1', reporterUid: 'student-2' })
      )
    );
    // Client cannot update fields on their report
    await assertFails(
      updateDoc(doc(reporterDb, 'reports', 'post-1_student-2'), {
        description: 'Edited reason',
      })
    );
    // Client cannot delete their report to re-submit
    await assertFails(deleteDoc(doc(reporterDb, 'reports', 'post-1_student-2')));
  });

  test('ST50: end-to-end integration flow — Android submits report -> Admin hides or keeps post -> Reporter & Author states', async () => {
    await seedCanonicalPost(testEnv, 'post-hide-flow', {
      authorId: 'student-1',
      moderationStatus: 'VISIBLE',
      version: 1,
    });
    await seedCanonicalPost(testEnv, 'post-keep-flow', {
      authorId: 'student-1',
      moderationStatus: 'VISIBLE',
      version: 1,
    });

    const authorDb = studentDb(testEnv, 'student-1', 'student1@ut.edu.vn');
    const reporterDb = studentDb(testEnv, 'student-2', 'student2@ut.edu.vn');

    // 1. Android reporter submits reports for both posts
    await assertSucceeds(
      setDoc(
        doc(reporterDb, 'reports', 'post-hide-flow_student-2'),
        makeReportData({
          postId: 'post-hide-flow',
          reporterUid: 'student-2',
          reason: 'INAPPROPRIATE',
          description: 'Hình ảnh không phù hợp quy định trường.',
        })
      )
    );
    await assertSucceeds(
      setDoc(
        doc(reporterDb, 'reports', 'post-keep-flow_student-2'),
        makeReportData({
          postId: 'post-keep-flow',
          reporterUid: 'student-2',
          reason: 'WRONG_INFO',
          description: 'Nghi ngờ sai khu vực.',
        })
      )
    );

    // 2. Scenario A: Web Admin keeps post-keep-flow (status -> REJECTED + publicNote)
    await seedDoc(testEnv, 'reports', 'post-keep-flow_student-2', {
      postId: 'post-keep-flow',
      reporterUid: 'student-2',
      reason: 'WRONG_INFO',
      description: 'Nghi ngờ sai khu vực.',
      status: 'REJECTED',
      publicNote: 'Đã xác minh thông tin bài viết chính xác.',
      createdAt: Timestamp.now(),
    });

    const keptReportSnap = await assertSucceeds(
      getDoc(doc(reporterDb, 'reports', 'post-keep-flow_student-2'))
    );
    expect(keptReportSnap.data().status).toBe('REJECTED');
    expect(keptReportSnap.data().publicNote).toBe('Đã xác minh thông tin bài viết chính xác.');

    // 3. Scenario B: Web Admin hides post-hide-flow (report -> RESOLVED_HIDDEN, post -> HIDDEN, post_moderations written)
    await seedDoc(testEnv, 'reports', 'post-hide-flow_student-2', {
      postId: 'post-hide-flow',
      reporterUid: 'student-2',
      reason: 'INAPPROPRIATE',
      description: 'Hình ảnh không phù hợp quy định trường.',
      status: 'RESOLVED_HIDDEN',
      publicNote: 'Đã ẩn bài viết vi phạm quy định.',
      createdAt: Timestamp.now(),
    });
    await seedCanonicalPost(testEnv, 'post-hide-flow', {
      authorId: 'student-1',
      moderationStatus: 'HIDDEN',
      version: 2,
    });
    await seedDoc(testEnv, 'post_moderations', 'post-hide-flow', {
      postId: 'post-hide-flow',
      authorId: 'student-1',
      moderationStatus: 'HIDDEN',
      reason: 'Vi phạm quy định hình ảnh cộng đồng UTH.',
      updatedAt: Timestamp.now(),
    });

    // Reporter sees RESOLVED_HIDDEN on their report, and can no longer read the hidden post
    const hiddenReportSnap = await assertSucceeds(
      getDoc(doc(reporterDb, 'reports', 'post-hide-flow_student-2'))
    );
    expect(hiddenReportSnap.data().status).toBe('RESOLVED_HIDDEN');
    await assertFails(getDoc(doc(reporterDb, 'posts', 'post-hide-flow')));

    // Author still reads their hidden post + moderation reason in My Posts, but cannot self-restore visibility
    const authorPostSnap = await assertSucceeds(
      getDoc(doc(authorDb, 'posts', 'post-hide-flow'))
    );
    expect(authorPostSnap.data().moderationStatus).toBe('HIDDEN');

    const authorModSnap = await assertSucceeds(
      getDoc(doc(authorDb, 'post_moderations', 'post-hide-flow'))
    );
    expect(authorModSnap.data().reason).toBe('Vi phạm quy định hình ảnh cộng đồng UTH.');

    await assertFails(
      updateDoc(doc(authorDb, 'posts', 'post-hide-flow'), {
        moderationStatus: 'VISIBLE',
      })
    );
  });
});
