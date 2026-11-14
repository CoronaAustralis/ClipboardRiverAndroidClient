package com.clipboardriver.client.platform

import org.junit.Assert.*
import org.junit.Test

class FileStorageTest {
    @Test fun uploadLimitsDistinguishDisabledUnlimitedAndExactBoundary() {
        assertTrue(fileExceedsUploadLimit(-1, 0))
        assertTrue(fileExceedsUploadLimit(-1, 1))
        assertFalse(fileExceedsUploadLimit(0, Long.MAX_VALUE))
        assertFalse(fileExceedsUploadLimit(5_242_880, 5_242_880))
        assertTrue(fileExceedsUploadLimit(5_242_880, 5_242_881))
    }

    @Test fun suppliedFileNamesCannotContainPathsOrControlCharacters() {
        assertEquals("origin name+.pdf", sanitizeFileName("origin name+.pdf"))
        assertEquals(".._.._bad_name.txt", sanitizeFileName("../../bad\nname.txt"))
        assertTrue(sanitizeFileName("a".repeat(300)).length <= 180)
    }
}