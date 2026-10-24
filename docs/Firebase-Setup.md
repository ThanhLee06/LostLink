# Firebase setup

This project uses Firebase Authentication (email/password) and Cloud Firestore. The app's Android application ID is `com.example.lostlink`. Image files are hosted on **Cloudinary** (free tier, avoiding the Firebase Blaze requirement) and their HTTPS URLs are saved inside Cloud Firestore documents.

## Connect the shared team project

1. In Firebase Console, open the shared LostLink project and add an Android app whose package name is `com.example.lostlink`.
2. Download that app's `google-services.json` and put it at `app/google-services.json`. (For this course project, `app/google-services.json` is committed to version control so all teammates can clone and run the app immediately; data access security is strictly enforced via Firebase Security Rules).
3. Enable **Email/Password** in Authentication → Sign-in method. Require users to verify their email before granting data access.
4. Create the Firestore database in the intended region.
5. Deploy the repository's rules from the `LostLink` directory with the Firebase CLI (only needs to be deployed once by one team member):

   ```bash
   firebase use <shared-project-id>
   firebase deploy --only firestore:rules
   ```

   Check that `<shared-project-id>` is the team's shared project before deploying. Do not put service-account keys in this repository.

The app's `FirebaseAccountRepository` provides registration, verification email, sign-in, verification refresh, and sign-out operations. Registration and sign-in accept only `@ut.edu.vn` addresses; the rules independently enforce both the domain and `email_verified` claim. After verifying an address, refresh the user's ID token (for example, sign out and sign in again) before their first protected data request so the verified claim is current.

## Initial data access model

- Firestore `posts`: verified `@ut.edu.vn` accounts may read; a verified account may create posts only with its own UID in `authorId` (including Cloudinary image URLs), and may edit/delete only its own posts.
- Firestore `users/{uid}`: only that verified account can read or write its own profile document.
- Media Storage: Handled by Cloudinary (images uploaded directly from app, returned HTTPS URL stored in Firestore `posts.imageUrl`).
- All other Firestore collections are denied until a specific policy is added.

These rules apply in Firebase itself, so hiding screens in the app is not the access control boundary. The initial policy allows verified students to read posts; if posts or images must be private to signed-in owners, narrow those read rules before deploying.

## Shared project checklist

Use the team's common Firebase project and have each teammate register their own verified `@ut.edu.vn` account. Give contributors project access through Firebase Console's project access controls; do not share account passwords. Never commit service-account credentials or admin keys. `app/google-services.json` is committed for shared project simplicity.
