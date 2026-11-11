const {
  Timestamp,
  assertFails,
  assertSucceeds,
  createPostAtomically,
  deleteDoc,
  deletePostAtomically,
  doc,
  getDoc,
  makeReportData,
  seedAccountState,
  seedCanonicalPost,
  seedDoc,
  setDoc,
  setupRulesTestEnv,
  studentDb,
  updateDoc,
  updatePostAtomically,
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
