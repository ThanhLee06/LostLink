package com.example.lostlink.ui.post

import android.content.Context
import android.net.Uri
import com.example.lostlink.data.PostCreationDataSource
import com.example.lostlink.data.PostRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostFormSubmissionTest {

    private class FakePostRepository(
        var uploadResult: Result<String?> = Result.success("https://test.cloudinary.com/image.jpg"),
        var createResult: Result<String> = Result.success("test-post-id-123")
    ) : PostCreationDataSource {
        var uploadCalls = 0
        var createCalls = 0
        var lastCachedUrl: String? = null

        override suspend fun uploadImageIfNeeded(context: Context, imageUri: Uri?, cachedUrl: String?): Result<String?> {
            uploadCalls++
            lastCachedUrl = cachedUrl
            if (!cachedUrl.isNullOrBlank()) return Result.success(cachedUrl)
            return uploadResult
        }

        override suspend fun createPost(state: PostFormState, imageUrl: String?): Result<String> {
            createCalls++
            return createResult
        }
    }

    @Test
    fun buildLocationString_formatsProperly() {
        val stateKnown = PostFormState(
            areaChoice = AreaChoice.KNOWN,
            areaId = "library",
            locationNote = "Tầng 2 gần cầu thang"
        )
        val locationStr = PostRepository.buildLocationString(stateKnown)
        assertEquals("Thư viện (Tầng 2 gần cầu thang)", locationStr)

        val stateOther = PostFormState(
            areaChoice = AreaChoice.OTHER,
            areaOtherText = "Sân bóng rổ",
            locationNote = ""
        )
        assertEquals("Sân bóng rổ", PostRepository.buildLocationString(stateOther))

        val stateUnknown = PostFormState(
            areaChoice = AreaChoice.UNKNOWN
        )
        assertEquals("Không rõ khu vực", PostRepository.buildLocationString(stateUnknown))
    }

    @Test
    fun submitPost_validationFails_doesNotCallRepository() = runBlocking {
        val fakeRepo = FakePostRepository()
        val vm = PostFormViewModel(repositoryProvider = { fakeRepo })

        var successId: String? = null
        val dummyContext = object : android.content.ContextWrapper(null) {}

        // Form trống -> validate fail
        vm.submitPost(dummyContext) { successId = it }

        assertEquals(0, fakeRepo.uploadCalls)
        assertEquals(0, fakeRepo.createCalls)
        assertNull(successId)
        assertFalse(vm.state.value.errors.isEmpty)
    }

    @Test
    fun clientPostId_isStableAcrossRetries() {
        val vm = PostFormViewModel()
        vm.loadSampleData()

        val initialId = vm.state.value.clientPostId
        assertNotNull(initialId)
        assertTrue(initialId.isNotBlank())

        // Người dùng chỉnh sửa mô tả hoặc thời gian -> clientPostId vẫn giữ nguyên ổn định
        vm.onDescriptionChange("Mô tả mới hơn một chút cho món đồ thất lạc")
        assertEquals(initialId, vm.state.value.clientPostId)

        // Chỉ khi clearDraft mới sinh mã mới
        vm.clearDraft()
        val newId = vm.state.value.clientPostId
        assertTrue(newId != initialId)
    }
}
