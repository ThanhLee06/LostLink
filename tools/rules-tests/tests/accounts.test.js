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
  makePostEventData,
  makeReportData,
  makeTombstoneData,
  orderBy,
  query,
  seedAccountState,
  seedCanonicalPost,
  seedDoc,
  setDoc,
  setupRulesTestEnv,
  studentDb,
  updateDoc,
  updatePostAtomically,
  where,
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

describe('account_states & deletion_requests rules (B0-T07, Contract §3.7)', () => {
  test('allows owner to read account_states and deletion_requests, denies non-owner and client writes', async () => {
    await seedAccountState(testEnv, 'student-1', 'DELETING');
    await seedDoc(testEnv, 'deletion_requests', 'student-1', {
      uid: 'student-1',
      status: 'DELETING',
      steps: { postsCleaned: false },
      createdAt: Timestamp.now(),
      updatedAt: Timestamp.now(),
    });

    const ownerDb = studentDb(testEnv, 'student-1', 'student1@ut.edu.vn');
    const otherDb = studentDb(testEnv, 'student-2', 'student2@ut.edu.vn');

    const accSnap = await assertSucceeds(
      getDoc(doc(ownerDb, 'account_states', 'student-1'))
    );
    expect(accSnap.data().status).toBe('DELETING');
    const delSnap = await assertSucceeds(
      getDoc(doc(ownerDb, 'deletion_requests', 'student-1'))
    );
    expect(delSnap.data().status).toBe('DELETING');

    await assertFails(getDoc(doc(otherDb, 'account_states', 'student-1')));
    await assertFails(getDoc(doc(otherDb, 'deletion_requests', 'student-1')));
    await assertFails(
      setDoc(doc(ownerDb, 'account_states', 'student-1'), {
        status: 'ACTIVE',
        updatedAt: Timestamp.now(),
      })
    );
    await assertFails(deleteDoc(doc(ownerDb, 'account_states', 'student-1')));
    await assertFails(
      updateDoc(doc(ownerDb, 'deletion_requests', 'student-1'), {
        status: 'COMPLETED',
      })
    );
  });

  test.each([['DELETING'], ['DELETED'], [null], ['CORRUPTED']])(
    'blocks normal writes across all client collections when account_states.status = %p',
    async (badStatus) => {
      await seedAccountState(testEnv, 'student-1', badStatus);
      await seedCanonicalPost(testEnv, 'post-owned', {
        authorId: 'student-1',
        version: 1,
      });
      await seedCanonicalPost(testEnv, 'post-other', {
        authorId: 'student-2',
        version: 1,
      });
      await seedDoc(testEnv, 'users', 'student-1', {
        uid: 'student-1',
        allowEmailContact: true,
      });
      await seedDoc(testEnv, 'saved_posts/student-1/items', 'post-other', {
        postId: 'post-other',
        savedAt: Timestamp.now(),
      });

      const db = studentDb(testEnv, 'student-1', 'student1@ut.edu.vn');

      // Blocked writes
      await assertFails(createPostAtomically(db, 'post-while-deleting'));
      await assertFails(
        updatePostAtomically(db, 'post-owned', 'student-1', 2, {
          title: 'Cannot edit while deleting',
        })
      );
      await assertFails(
        deletePostAtomically(db, 'post-owned', 'student-1', 2)
      );
      await assertFails(
        setDoc(
          doc(db, 'reports', 'post-other_student-1'),
          makeReportData({ postId: 'post-other', reporterUid: 'student-1' })
        )
      );
      await assertFails(
        updateDoc(doc(db, 'users', 'student-1'), { allowEmailContact: false })
      );
      await assertFails(
        setDoc(doc(db, 'saved_posts', 'student-1', 'items', 'post-owned'), {
          postId: 'post-owned',
        })
      );
      await assertFails(
        deleteDoc(doc(db, 'saved_posts', 'student-1', 'items', 'post-other'))
      );

      // Owner reads remain allowed during DELETING (Contract §3.7)
      await assertSucceeds(getDoc(doc(db, 'account_states', 'student-1')));
      await assertSucceeds(getDoc(doc(db, 'users', 'student-1')));
      await assertSucceeds(
        getDoc(doc(db, 'saved_posts', 'student-1', 'items', 'post-other'))
      );
      await assertSucceeds(getDoc(doc(db, 'posts', 'post-owned')));
    }
  );
});

describe('ST35 — DELETING lock after account deletion request', () => {
  async function seedDeletingAccount() {
    await seedAccountState(testEnv, 'student-1', 'DELETING');
    await seedDoc(testEnv, 'deletion_requests', 'student-1', {
      uid: 'student-1',
      requestId: 'req-1',
      status: 'QUEUED',
      steps: {
        postsHidden: { status: 'DONE', hiddenCount: 1 },
        sessionsRevoked: { status: 'DONE' },
      },
      cleanupJob: {
        status: 'PENDING',
        attempts: 0,
        claimToken: null,
        lockOwner: null,
        lockExpiresAt: null,
        nextRetryAt: null,
        lastError: null,
      },
      createdAt: Timestamp.now(),
      updatedAt: Timestamp.now(),
    });
    // Post hidden by the backend during the lock phase
    await seedCanonicalPost(testEnv, 'post-hidden-by-deletion', {
      authorId: 'student-1',
      moderationStatus: 'HIDDEN',
      version: 2,
    });
    await seedDoc(testEnv, 'post_moderations', 'post-hidden-by-deletion', {
      postId: 'post-hidden-by-deletion',
      authorId: 'student-1',
      moderationStatus: 'HIDDEN',
      reason: 'ACCOUNT_DELETING',
      updatedAt: Timestamp.now(),
    });
    await seedCanonicalPost(testEnv, 'post-visible-other', {
      authorId: 'student-2',
      contactEmail: 'student2@ut.edu.vn',
    });
  }

  test('blocks brand-new posts, events, tombstones, reports and profile writes', async () => {
    await seedDeletingAccount();
    const db = studentDb(testEnv, 'student-1', 'student1@ut.edu.vn');

    await assertFails(createPostAtomically(db, 'post-new-during-deleting'));
    await assertFails(
      setDoc(
        doc(db, 'post_events', 'post-new-2_v1'),
        makePostEventData({ postId: 'post-new-2' })
      )
    );
    await assertFails(
      setDoc(doc(db, 'post_tombstones', 'post-hidden-by-deletion'), makeTombstoneData())
    );
    await assertFails(
      setDoc(
        doc(db, 'reports', 'post-visible-other_student-1'),
        makeReportData({ postId: 'post-visible-other', reporterUid: 'student-1' })
      )
    );
    await assertFails(
      setDoc(doc(db, 'users', 'student-1'), { uid: 'student-1', allowEmailContact: true })
    );
    await assertFails(
      setDoc(doc(db, 'saved_posts', 'student-1', 'items', 'post-visible-other'), {
        postId: 'post-visible-other',
      })
    );
    // Cannot un-lock itself or tamper with the deletion job
    await assertFails(
      updateDoc(doc(db, 'account_states', 'student-1'), { status: 'ACTIVE' })
    );
    await assertFails(
      updateDoc(doc(db, 'deletion_requests', 'student-1'), { status: 'CANCELLED' })
    );
    await assertFails(deleteDoc(doc(db, 'deletion_requests', 'student-1')));
  });

  test('owner can still read own deletion state, account state and hidden posts', async () => {
    await seedDeletingAccount();
    const db = studentDb(testEnv, 'student-1', 'student1@ut.edu.vn');

    const state = await assertSucceeds(getDoc(doc(db, 'account_states', 'student-1')));
    expect(state.data().status).toBe('DELETING');
    const job = await assertSucceeds(getDoc(doc(db, 'deletion_requests', 'student-1')));
    expect(job.data().status).toBe('QUEUED');
    await assertSucceeds(getDoc(doc(db, 'posts', 'post-hidden-by-deletion')));
    const mod = await assertSucceeds(
      getDoc(doc(db, 'post_moderations', 'post-hidden-by-deletion'))
    );
    expect(mod.data().reason).toBe('ACCOUNT_DELETING');
  });

  test('hidden posts of a deleting account disappear from other students feed', async () => {
    await seedDeletingAccount();
    const otherDb = studentDb(testEnv, 'student-2', 'student2@ut.edu.vn');

    await assertFails(getDoc(doc(otherDb, 'posts', 'post-hidden-by-deletion')));
    await assertFails(getDoc(doc(otherDb, 'post_moderations', 'post-hidden-by-deletion')));
    await assertFails(getDoc(doc(otherDb, 'deletion_requests', 'student-1')));

    const feed = await assertSucceeds(
      getDocs(
        query(
          collection(otherDb, 'posts'),
          where('moderationStatus', '==', 'VISIBLE'),
          where('status', 'in', ['OPEN', 'RESOLVED']),
          orderBy('createdAt', 'desc'),
          limit(20)
        )
      )
    );
    const ids = feed.docs.map((d) => d.id);
    expect(ids).toContain('post-visible-other');
    expect(ids).not.toContain('post-hidden-by-deletion');
  });
});
