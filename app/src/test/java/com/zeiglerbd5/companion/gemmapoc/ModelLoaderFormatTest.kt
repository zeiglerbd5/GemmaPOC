package com.zeiglerbd5.companion.gemmapoc

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-logic coverage for the download progress readout: byte formatting
 * and fraction math. The strings feed the "34% — 890 MB / 2.53 GB" line
 * on the first-launch download card.
 */
class ModelLoaderFormatTest {

    // MARK: formatBytes

    @Test
    fun `zero bytes renders as 0 MB`() {
        assertEquals("0 MB", ModelLoader.formatBytes(0L))
    }

    @Test
    fun `sub-GiB values render as whole megabytes`() {
        assertEquals("890 MB", ModelLoader.formatBytes(890L * 1_048_576))
    }

    @Test
    fun `values at or above 1 GiB render as GB with two decimals`() {
        assertEquals("1.00 GB", ModelLoader.formatBytes(1024L * 1_048_576))
        // 2.53 GB — the model's approximate on-disk size.
        assertEquals("2.53 GB", ModelLoader.formatBytes((2.53 * 1024 * 1_048_576).toLong()))
    }

    @Test
    fun `just under 1 GiB stays in MB`() {
        assertEquals("1023 MB", ModelLoader.formatBytes(1023L * 1_048_576))
    }

    // MARK: DownloadProgress.fractionCompleted

    @Test
    fun `fraction is bytesDone over bytesTotal`() {
        val p = ModelLoader.DownloadProgress(bytesDone = 25, bytesTotal = 100)
        assertEquals(0.25, p.fractionCompleted, 1e-9)
    }

    @Test
    fun `fraction is zero when total is unknown`() {
        // Content-Length missing → HttpURLConnection reports -1.
        val p = ModelLoader.DownloadProgress(bytesDone = 500, bytesTotal = -1)
        assertEquals(0.0, p.fractionCompleted, 1e-9)
    }

    @Test
    fun `fraction reaches one at completion`() {
        val p = ModelLoader.DownloadProgress(bytesDone = 1000, bytesTotal = 1000)
        assertEquals(1.0, p.fractionCompleted, 1e-9)
    }
}
