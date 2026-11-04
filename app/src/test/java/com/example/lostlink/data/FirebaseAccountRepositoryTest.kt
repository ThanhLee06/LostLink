package com.example.lostlink.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirebaseAccountRepositoryTest {

    @Test
    fun isAllowedEmail_returnsTrueForUtEduVnEmails() {
        assertTrue(FirebaseAccountRepository.isAllowedEmail("2151050001@ut.edu.vn"))
        assertTrue(FirebaseAccountRepository.isAllowedEmail("sinhvien@ut.edu.vn"))
        assertTrue(FirebaseAccountRepository.isAllowedEmail("TEST.STUDENT@UT.EDU.VN"))
        assertTrue(FirebaseAccountRepository.isAllowedEmail("  2151050123.sv@ut.edu.vn  "))
    }

    @Test
    fun isAllowedEmail_returnsFalseForOtherDomains() {
        assertFalse(FirebaseAccountRepository.isAllowedEmail("student@gmail.com"))
        assertFalse(FirebaseAccountRepository.isAllowedEmail("student@ut.edu.com"))
        assertFalse(FirebaseAccountRepository.isAllowedEmail("student@ut.edu"))
        assertFalse(FirebaseAccountRepository.isAllowedEmail(""))
    }
}
