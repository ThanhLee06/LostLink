const fs = require('fs');
const path = require('path');
const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require('@firebase/rules-unit-testing');
const {
  Timestamp,
  collection,
  deleteDoc,
  doc,
  getDoc,
  getDocs,
  limit,
  orderBy,
  query,
  serverTimestamp,
  setDoc,
  setLogLevel,
  updateDoc,
  where,
  writeBatch,
} = require('firebase/firestore');

const PROJECT_ID = 'demo-lostlink';
const RULES_PATH = path.resolve(__dirname, '../../../firestore.rules');

function getFirestoreEmulatorHostPort() {
  const raw = process.env.FIRESTORE_EMULATOR_HOST || '127.0.0.1:8088';
  const lastColon = raw.lastIndexOf(':');
  if (lastColon <= 0) {
    return { host: '127.0.0.1', port: 8088 };
  }
  const host = raw.slice(0, lastColon);
  const port = Number(raw.slice(lastColon + 1));
  if (!Number.isInteger(port) || port <= 0) {
    return { host: '127.0.0.1', port: 8088 };
  }
  return { host, port };
}

async function setupRulesTestEnv() {
  setLogLevel('error');
  const { host, port } = getFirestoreEmulatorHostPort();
  const rules = fs.readFileSync(RULES_PATH, 'utf8');
  return initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: { host, port, rules },
  });
}

function studentDb(testEnv, uid = 'student-1', email = 'student1@ut.edu.vn', extraClaims = {}) {
  return testEnv
    .authenticatedContext(uid, {
      email,
      email_verified: true,
      ...extraClaims,
    })
    .firestore();
}

function rawAuthedDb(testEnv, uid, tokenOptions = {}) {
  return testEnv.authenticatedContext(uid, tokenOptions).firestore();
}

function unauthDb(testEnv) {
  return testEnv.unauthenticatedContext().firestore();
}

function makeInitialJobMap(overrides = {}) {
  return {
    status: 'PENDING',
    attempts: 0,
    claimToken: null,
    lockOwner: null,
    lockExpiresAt: null,
    nextRetryAt: null,
    lastError: null,
    ...overrides,
  };
}

function makePostData(overrides = {}) {
  return {
    title: 'Balo xanh rớt ở thư viện',
    type: 'LOST',
    categoryId: 'bag',
    category: 'Balo/Túi xách',
    description: 'Balo màu xanh dương có móc khóa đỏ bị rơi gần bàn đọc sách.',
    areaChoice: 'KNOWN',
    areaId: 'library',
    areaOtherText: '',
    location: 'Thư viện',
    locationNote: 'Tầng 2 gần khu tự học',
    occurredAt: '2026-11-12 08:30',
    timeMode: 'EXACT',
    exactAtMillis: 1762911000000,
    rangeStartMillis: null,
    rangeEndMillis: null,
    timeNote: '',
    status: 'OPEN',
    moderationStatus: 'VISIBLE',
    version: 1,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
    authorId: 'student-1',
    authorName: 'Nguyễn Văn A',
    contactEmail: 'student1@ut.edu.vn',
    allowContact: true,
    mediaAssetIds: [],
    imageUrl: null,
    imageUrls: [],
    imagePublicId: null,
    ...overrides,
  };
}

function makePostEventData(overrides = {}) {
  return {
    postId: 'post-1',
    authorId: 'student-1',
    postVersion: 1,
    changeType: 'CREATE',
    createdAt: serverTimestamp(),
    imageAssetRefs: [],
    searchJob: makeInitialJobMap(),
    notifyJob: makeInitialJobMap(),
    mediaCleanupJob: makeInitialJobMap(),
    ...overrides,
  };
}

function makeTombstoneData(overrides = {}) {
  return {
    authorId: 'student-1',
    deletedAt: serverTimestamp(),
    ...overrides,
  };
}

function makeReportData(overrides = {}) {
  return {
    postId: 'post-1',
    reporterUid: 'student-2',
    reason: 'SPAM',
    description: 'Nội dung trùng lặp nhiều lần.',
    status: 'PENDING',
    createdAt: serverTimestamp(),
    ...overrides,
  };
}

async function seedDoc(testEnv, collectionPath, docId, data) {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), collectionPath, docId), data);
  });
}

async function seedAccountState(testEnv, uid, status) {
  await seedDoc(testEnv, 'account_states', uid, {
    status,
    updatedAt: Timestamp.now(),
  });
}

async function seedUserProfile(testEnv, uid, overrides = {}) {
  await seedDoc(testEnv, 'users', uid, {
    uid,
    email: `${uid}@ut.edu.vn`,
    displayName: 'Sinh viên Test',
    allowEmailContact: true,
    ...overrides,
  });
}

async function seedMediaAsset(testEnv, assetId, overrides = {}) {
  const ownerUid = overrides.ownerUid || 'student-1';
  const cloudinaryPublicId = overrides.cloudinaryPublicId || `lostlink/${ownerUid}/${assetId}`;
  await seedDoc(testEnv, 'media_assets', assetId, {
    assetId,
    ownerUid,
    postId: 'post-1',
    cloudinaryPublicId,
    cloudinaryAssetId: `cl-${assetId}`,
    secureUrl: `https://res.cloudinary.com/demo/image/upload/v1/${cloudinaryPublicId}.jpg`,
    status: 'READY',
    createdAt: Timestamp.now(),
    ...overrides,
  });
}

async function seedCanonicalPost(testEnv, postId, overrides = {}) {
  const now = Timestamp.now();
  await seedDoc(
    testEnv,
    'posts',
    postId,
    makePostData({
      createdAt: now,
      updatedAt: now,
      ...overrides,
    })
  );
}

async function createPostAtomically(db, postId, postOverrides = {}, eventOverrides = {}) {
  const batch = writeBatch(db);
  const postData = makePostData(postOverrides);
  const eventData = makePostEventData({
    postId,
    authorId: postData.authorId,
    postVersion: postData.version,
    changeType: 'CREATE',
    imageAssetRefs: Array.isArray(postData.mediaAssetIds) ? postData.mediaAssetIds : [],
    ...eventOverrides,
  });
  batch.set(doc(db, 'posts', postId), postData);
  batch.set(doc(db, 'post_events', `${postId}_v${eventData.postVersion}`), eventData);
  return batch.commit();
}

async function updatePostAtomically(
  db,
  postId,
  authorId,
  nextVersion,
  postPatch = {},
  eventOverrides = {}
) {
  const batch = writeBatch(db);
  batch.update(doc(db, 'posts', postId), {
    version: nextVersion,
    updatedAt: serverTimestamp(),
    ...postPatch,
  });
  const eventData = makePostEventData({
    postId,
    authorId,
    postVersion: nextVersion,
    changeType: 'UPDATE',
    ...eventOverrides,
  });
  batch.set(doc(db, 'post_events', `${postId}_v${eventData.postVersion}`), eventData);
  return batch.commit();
}

async function deletePostAtomically(
  db,
  postId,
  authorId,
  nextVersion,
  eventOverrides = {},
  tombstoneOverrides = {}
) {
  const batch = writeBatch(db);
  batch.delete(doc(db, 'posts', postId));
  const eventData = makePostEventData({
    postId,
    authorId,
    postVersion: nextVersion,
    changeType: 'DELETE',
    ...eventOverrides,
  });
  batch.set(doc(db, 'post_events', `${postId}_v${eventData.postVersion}`), eventData);
  batch.set(
    doc(db, 'post_tombstones', postId),
    makeTombstoneData({
      authorId,
      ...tombstoneOverrides,
    })
  );
  return batch.commit();
}

module.exports = {
  PROJECT_ID,
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
  makeInitialJobMap,
  makePostData,
  makePostEventData,
  makeReportData,
  makeTombstoneData,
  orderBy,
  query,
  rawAuthedDb,
  seedAccountState,
  seedCanonicalPost,
  seedDoc,
  seedMediaAsset,
  seedUserProfile,
  serverTimestamp,
  setDoc,
  setupRulesTestEnv,
  studentDb,
  unauthDb,
  updateDoc,
  updatePostAtomically,
  where,
  writeBatch,
};
