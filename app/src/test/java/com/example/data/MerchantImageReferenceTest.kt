package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MerchantImageReferenceTest {
    @Test
    fun `prefers a direct merchant image URL over a storage path`() {
        assertEquals(
            "https://cdn.example.test/merchant-logo.jpg",
            resolveMerchantImageReference(
                "https://cdn.example.test/merchant-logo.jpg",
                "merchants/merchant-1/logo.jpg"
            )
        )
    }

    @Test
    fun `uses the storage object path when no direct URL exists`() {
        assertEquals(
            "merchants/merchant-1/logo.jpg",
            resolveMerchantImageReference("", "merchants/merchant-1/logo.jpg")
        )
    }

    @Test
    fun `preserves a storage reference when no separate path is supplied`() {
        assertEquals(
            "gs://el-batal-express-956ae.firebasestorage.app/merchants/merchant-1/logo.jpg",
            resolveMerchantImageReference(
                "gs://el-batal-express-956ae.firebasestorage.app/merchants/merchant-1/logo.jpg",
                null
            )
        )
    }

    @Test
    fun `returns empty when both merchant image references are empty`() {
        assertEquals("", resolveMerchantImageReference(" ", null))
    }
}
