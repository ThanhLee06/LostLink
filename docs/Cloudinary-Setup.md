# Cloudinary Image Upload Setup

LostLink uses **Cloudinary** (Unsigned Upload) for hosting image attachments in post items. Image files are uploaded directly from the mobile app to Cloudinary, and the returned HTTPS `secure_url` is saved in Cloud Firestore documents (`posts.imageUrl`).

## Requirements & Security Model

1. **Unsigned Upload Preset**: Authentication relies solely on `cloud_name` and an Unsigned `upload_preset`.
2. **No API Keys in Repository**: Neither API Keys nor API Secrets are stored in the codebase or version control.
3. **Shared Credentials Configuration**: Shared credentials are configured in `cloudinary.properties` (committed in repository). Individual developers can optionally override these locally in `local.properties` (which is git-ignored).

## Configuration Steps

1. For this course project, shared Cloudinary credentials are listed in `cloudinary.properties` in the project root.
2. (Optional) To override settings locally on your machine, open or create `local.properties` in the project root (or copy from `local.properties.example`):

   ```properties
   CLOUDINARY_CLOUD_NAME=your_cloud_name
   CLOUDINARY_UPLOAD_PRESET=your_unsigned_preset_name
   ```

3. Sync Gradle in Android Studio (`Sync Project with Gradle Files`).

## Usage in Code (`CloudinaryRepository`)

```kotlin
val cloudinaryRepository = CloudinaryRepository()

// Upload image (returns Result<String?> containing secure_url)
val uploadResult = cloudinaryRepository.uploadImage(context, selectedImageUri)

uploadResult.onSuccess { secureUrl ->
    // secureUrl is null if no image was selected, or a valid https://res.cloudinary.com/... URL
    savePostToFirestore(title = "Lost Key", imageUrl = secureUrl)
}.onFailure { error ->
    // Handles network errors, invalid image types (only JPG/PNG/WEBP), or size limit (> 5 MB)
    showToast(error.localizedMessage)
}
```

## Validation & Limits

- **File Types**: Only JPG, PNG, and WebP images are permitted.
- **Max File Size**: 5 MB limit enforced on client side prior to upload.
- **Optional Images**: `imageUrl` is nullable (`String?`); posts without images remain completely valid.
- **URL Security**: Only HTTPS `secure_url` strings returned by Cloudinary are stored in Firestore.
