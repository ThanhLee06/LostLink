package com.example.lostlink.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudinaryRepositoryTest {

    @Test
    fun uploadImage_withNullUri_returnsSuccessWithNull() = runBlocking {
        val repository = CloudinaryRepository(cloudName = "test_cloud", uploadPreset = "test_preset")
        val result = repository.uploadImage(
            context = object : android.content.ContextWrapper(null) {},
            imageUri = null,
        )
        assertTrue(result.isSuccess)
        assertEquals(null, result.getOrNull())
    }
}
