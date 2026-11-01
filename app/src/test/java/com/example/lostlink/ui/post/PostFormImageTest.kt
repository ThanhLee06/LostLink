package com.example.lostlink.ui.post

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostFormImageTest {

    @Test
    fun imageIsOptional_validationSucceedsWithoutImage() {
        // Kiểm tra yêu cầu: Ảnh là tùy chọn cho cả hai loại trong thời gian đầu
        val stateLost = PostFormState(
            type = PostType.LOST,
            title = "Ví da màu đen",
            categoryId = "wallet",
            description = "Ví da nam màu đen để quên trên bàn học tầng 2",
            areaChoice = AreaChoice.UNKNOWN,
            timeMode = TimeMode.UNKNOWN,
            imageUri = null
        )
        val errorsLost = PostFormValidator.validate(stateLost)
        assertTrue("Validation phải thành công khi không có ảnh cho bài LOST", errorsLost.isEmpty)

        val stateFound = stateLost.copy(type = PostType.FOUND)
        val errorsFound = PostFormValidator.validate(stateFound)
        assertTrue("Validation phải thành công khi không có ảnh cho bài FOUND", errorsFound.isEmpty)
    }

    @Test
    fun viewModel_initialImageStateIsNull() {
        val vm = PostFormViewModel()
        assertNull("imageUri ban đầu phải là null", vm.state.value.imageUri)
        assertNull("errors.image ban đầu phải là null", vm.state.value.errors.image)
        assertFalse("Ban đầu chưa có thay đổi", vm.state.value.hasUnsavedChanges)
    }

    @Test
    fun viewModel_cancelPicker_preservesState() {
        // Kiểm tra yêu cầu: Nếu hủy bỏ bộ ảnh đã chọn, hãy quay lại biểu mẫu và giữ dữ liệu nhập vào
        val vm = PostFormViewModel()
        vm.onTitleChange("Chìa khóa xe máy")
        vm.onDescriptionChange("Chìa khóa có móc treo hình con gấu")

        // Giả lập người dùng mở photo picker rồi bấm back/cancel (trả về null)
        vm.onImageSelected(null)

        assertEquals("Chìa khóa xe máy", vm.state.value.title)
        assertEquals("Chìa khóa có móc treo hình con gấu", vm.state.value.description)
        assertNull(vm.state.value.imageUri)
    }

    @Test
    fun viewModel_onImageError_setsErrorMessage() {
        // Kiểm tra yêu cầu: Nếu hình ảnh không thể đọc hoặc quá lớn, xác định và hiển thị lỗi
        val vm = PostFormViewModel()
        val errorMsg = "Ảnh quá lớn (6.2 MB). Vui lòng chọn ảnh dưới 5 MB."

        vm.onImageError(errorMsg)
        assertEquals(errorMsg, vm.state.value.errors.image)

        // Xóa lỗi
        vm.clearImageError()
        assertNull(vm.state.value.errors.image)
    }

    @Test
    fun viewModel_removeImage_clearsImageAndErrors() {
        val vm = PostFormViewModel()
        vm.onImageError("Lỗi ảnh cũ")
        vm.onRemoveImage()

        assertNull(vm.state.value.imageUri)
        assertNull(vm.state.value.errors.image)
    }
}
