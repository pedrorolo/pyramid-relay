package p2p.broadcaster

import io.mockk.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class FileServiceTest {

    private lateinit var tmpDir: File

    @Before
    fun setup() {
        tmpDir = File(System.getProperty("java.io.tmpdir"), "fileservice_test_${System.nanoTime()}")
        tmpDir.mkdirs()
    }

    private fun createService(): FileService {
        val context = mockk<android.content.Context>(relaxed = true)
        every { context.filesDir } returns tmpDir
        return FileService(context)
    }

    @Test
    fun `spec 6 - evictOldVersions keeps only specified version`() {
        val svc = createService()
        val fileId = "test-file-1"
        val v1 = File(tmpDir, "store/$fileId/v1")
        val v2 = File(tmpDir, "store/$fileId/v2")
        v1.mkdirs(); v2.mkdirs()
        File(v1, "file").writeText("old")
        File(v2, "file").writeText("new")

        svc.evictOldVersions(fileId, 2)

        assertFalse(File(v1, "file").exists())
        assertFalse(v1.exists())
        assertTrue(File(v2, "file").exists())
    }

    @Test
    fun `spec 6 - evictOldVersions does nothing when dir missing`() {
        val svc = createService()
        svc.evictOldVersions("nonexistent", 1)
    }

    @Test
    fun `spec 6 - evictOldVersions keeps target version`() {
        val svc = createService()
        val fileId = "test-file-2"
        val v1 = File(tmpDir, "store/$fileId/v1")
        v1.mkdirs()
        File(v1, "file").writeText("content")

        svc.evictOldVersions(fileId, 1)

        assertTrue(File(v1, "file").exists())
    }

    @Test
    fun `spec 6 - evictOldVersions with multiple old versions`() {
        val svc = createService()
        val fileId = "test-file-3"
        listOf(1, 2, 3).forEach { v ->
            val dir = File(tmpDir, "store/$fileId/v$v")
            dir.mkdirs()
            File(dir, "file").writeText("content v$v")
        }

        svc.evictOldVersions(fileId, 3)

        assertFalse(File(tmpDir, "store/$fileId/v1").exists())
        assertFalse(File(tmpDir, "store/$fileId/v2").exists())
        assertTrue(File(tmpDir, "store/$fileId/v3/file").exists())
    }

    @Test
    fun `spec 6 - deleteAll removes entire broadcast directory`() {
        val svc = createService()
        val fileId = "test-file-4"
        val v1 = File(tmpDir, "store/$fileId/v1")
        v1.mkdirs()
        File(v1, "file").writeText("data")

        svc.deleteAll(fileId)

        assertFalse(File(tmpDir, "store/$fileId").exists())
    }

    @Test
    fun `spec 6 - deleteAll on nonexistent fileId is safe`() {
        val svc = createService()
        svc.deleteAll("nonexistent")
    }

    @Test
    fun `spec 6 - hasFile returns true for existing file`() {
        val svc = createService()
        val fileId = "test-file-5"
        val v1 = File(tmpDir, "store/$fileId/v1")
        v1.mkdirs()
        File(v1, "file").writeText("data")

        assertTrue(svc.hasFile(fileId, 1))
    }

    @Test
    fun `spec 6 - hasFile returns false for missing file`() {
        val svc = createService()
        assertFalse(svc.hasFile("nonexistent", 1))
    }

    @Test
    fun `spec 6 - hasFile returns false for wrong version`() {
        val svc = createService()
        val fileId = "test-file-6"
        val v1 = File(tmpDir, "store/$fileId/v1")
        v1.mkdirs()
        File(v1, "file").writeText("data")

        assertFalse(svc.hasFile(fileId, 2))
    }

    @Test
    fun `spec 6 - getTmpFile and getFile paths are correct`() {
        val svc = createService()
        val tmpFile = svc.getTmpFile("fid", 2)
        assertTrue(tmpFile.path.endsWith("v2/file.tmp"))
        val file = svc.getFile("fid", 2)
        assertTrue(file.path.endsWith("v2/file"))
    }

    @Test
    fun `spec 6 - getVersionDir path matches spec format`() {
        val svc = createService()
        val dir = svc.getVersionDir("fid", 3)
        assertTrue(dir.path.endsWith("store/fid/v3"))
    }

    @Test
    fun `spec 6 - getStoreDir returns store dir`() {
        val svc = createService()
        assertTrue(svc.getStoreDir().path.endsWith("store"))
    }
}
