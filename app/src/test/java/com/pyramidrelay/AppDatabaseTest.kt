package com.pyramidrelay

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppDatabaseTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var db: AppDatabase
    private lateinit var broadcastDao: BroadcastDao
    private lateinit var subscriptionDao: SubscriptionDao

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = AppDatabase(context)
        broadcastDao = BroadcastDao(db)
        subscriptionDao = SubscriptionDao(db)
    }

    @After
    fun teardown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `broadcastDao upsert and getAll returns entity`() = runTest {
        val broadcast = BroadcastEntity(
            "file-1", "test.txt", "text/plain", "/path", "hash1",
            1024L, 1024L, 1, "pk1", "sk1", "sig1",
            Role.ORIGINATOR, 100L, 200L
        )
        broadcastDao.upsert(broadcast)
        val all = broadcastDao.getAll()
        assertEquals(1, all.size)
        assertEquals("file-1", all[0].fileId)
        assertEquals("test.txt", all[0].fileName)
        assertEquals("text/plain", all[0].mimeType)
        assertEquals("/path", all[0].internalUri)
        assertEquals("hash1", all[0].fileHash)
        assertEquals(1024L, all[0].fileSize)
        assertEquals(1, all[0].version)
        assertEquals("pk1", all[0].publicKey)
        assertEquals("sk1", all[0].privateKeyAlias)
        assertEquals("sig1", all[0].signature)
        assertEquals(Role.ORIGINATOR, all[0].role)
        assertEquals(100L, all[0].createdAt)
        assertEquals(200L, all[0].updatedAt)
    }

    @Test
    fun `broadcastDao upsert replaces existing entity`() = runTest {
        val v1 = BroadcastEntity("f1", "a.txt", "t", "/p", "h", 100, 100, 1, "pk", null, "s", Role.ORIGINATOR, 0, 0)
        val v2 = BroadcastEntity("f1", "b.txt", "t2", "/p2", "h2", 200, 200, 2, "pk2", "sk2", "s2", Role.ORIGINATOR, 1, 2)
        broadcastDao.upsert(v1)
        broadcastDao.upsert(v2)
        val all = broadcastDao.getAll()
        assertEquals(1, all.size)
        assertEquals("b.txt", all[0].fileName)
        assertEquals(2, all[0].version)
    }

    @Test
    fun `broadcastDao getById returns null for missing`() = runTest {
        assertNull(broadcastDao.getById("nonexistent"))
    }

    @Test
    fun `broadcastDao getById returns entity`() = runTest {
        val broadcast = BroadcastEntity("f1", "a", "t", "/p", "h", 100, 100, 1, "pk", null, "s", Role.ORIGINATOR, 0, 0)
        broadcastDao.upsert(broadcast)
        val result = broadcastDao.getById("f1")
        assertNotNull(result)
        assertEquals("f1", result!!.fileId)
    }

    @Test
    fun `broadcastDao delete removes entity`() = runTest {
        val broadcast = BroadcastEntity("f1", "a", "t", "/p", "h", 100, 100, 1, "pk", null, "s", Role.ORIGINATOR, 0, 0)
        broadcastDao.upsert(broadcast)
        broadcastDao.delete("f1")
        assertEquals(0, broadcastDao.getAll().size)
    }

    @Test
    fun `broadcastDao delete nonexistent is safe`() = runTest {
        broadcastDao.delete("nonexistent")
    }

    @Test
    fun `broadcastDao updateVersion modifies version and hash`() = runTest {
        val broadcast = BroadcastEntity("f1", "a", "t", "/p", "old_hash", 100, 100, 1, "pk", null, "s", Role.ORIGINATOR, 0, 0)
        broadcastDao.upsert(broadcast)
        broadcastDao.updateVersion("f1", 2, "new_hash", "new_sig", "/new_path", 200L, 200L, 999L)
        val result = broadcastDao.getById("f1")!!
        assertEquals(2, result.version)
        assertEquals("new_hash", result.fileHash)
        assertEquals("new_sig", result.signature)
        assertEquals("/new_path", result.internalUri)
        assertEquals(200L, result.fileSize)
        assertEquals(999L, result.updatedAt)
    }

    @Test
    fun `broadcastDao getAll returns empty for empty table`() = runTest {
        assertTrue(broadcastDao.getAll().isEmpty())
    }

    @Test
    fun `broadcastDao changeFlow emits on upsert`() = runTest {
        broadcastDao.upsert(BroadcastEntity("f1", "a", "t", "/p", "h", 100, 100, 1, "pk", null, "s", Role.ORIGINATOR, 0, 0))
        advanceUntilIdle()
        assertTrue(broadcastDao.changeFlow.value > 0)
    }

    @Test
    fun `broadcastDao changeFlow emits on delete`() = runTest {
        broadcastDao.upsert(BroadcastEntity("f1", "a", "t", "/p", "h", 100, 100, 1, "pk", null, "s", Role.ORIGINATOR, 0, 0))
        advanceUntilIdle()
        val before = broadcastDao.changeFlow.value
        broadcastDao.delete("f1")
        advanceUntilIdle()
        assertTrue(broadcastDao.changeFlow.value > before)
    }

    @Test
    fun `subscriptionDao upsert and getAll returns entity`() = runTest {
        val sub = SubscriptionEntity("sub-1", "pk1", "file.txt", 3, "/path", 1000L, 5, 2000L, 4)
        subscriptionDao.upsert(sub)
        val all = subscriptionDao.getAll()
        assertEquals(1, all.size)
        assertEquals("sub-1", all[0].fileId)
        assertEquals("pk1", all[0].publicKey)
        assertEquals("file.txt", all[0].fileName)
        assertEquals(3, all[0].localVersion)
        assertEquals("/path", all[0].localUri)
        assertEquals(1000L, all[0].subscribedAt)
        assertEquals(5, all[0].lastSeenVersion)
        assertEquals(2000L, all[0].lastSeenAt)
        assertEquals(4, all[0].lastNotifiedVersion)
    }

    @Test
    fun `subscriptionDao upsert replaces existing entity`() = runTest {
        val v1 = SubscriptionEntity("s1", "pk", "old.txt", null, null, 0, null, null, null)
        val v2 = SubscriptionEntity("s1", "pk2", "new.txt", 1, "/p", 1, null, null, null)
        subscriptionDao.upsert(v1)
        subscriptionDao.upsert(v2)
        val all = subscriptionDao.getAll()
        assertEquals(1, all.size)
        assertEquals("new.txt", all[0].fileName)
        assertEquals(1, all[0].localVersion)
    }

    @Test
    fun `subscriptionDao getById returns null for missing`() = runTest {
        assertNull(subscriptionDao.getById("nonexistent"))
    }

    @Test
    fun `subscriptionDao getById returns entity`() = runTest {
        subscriptionDao.upsert(SubscriptionEntity("s1", "pk", "f", null, null, 0, null, null, null))
        val result = subscriptionDao.getById("s1")
        assertNotNull(result)
        assertEquals("s1", result!!.fileId)
    }

    @Test
    fun `subscriptionDao delete removes entity`() = runTest {
        subscriptionDao.upsert(SubscriptionEntity("s1", "pk", "f", null, null, 0, null, null, null))
        subscriptionDao.delete("s1")
        assertTrue(subscriptionDao.getAll().isEmpty())
    }

    @Test
    fun `subscriptionDao delete nonexistent is safe`() = runTest {
        subscriptionDao.delete("nonexistent")
    }

    @Test
    fun `subscriptionDao updateReceived updates fields`() = runTest {
        subscriptionDao.upsert(SubscriptionEntity("s1", "pk", "f", null, null, 0, null, null, null))
        subscriptionDao.updateReceived("s1", 3, "/file/path", 3, 5000L)
        val result = subscriptionDao.getById("s1")!!
        assertEquals(3, result.localVersion)
        assertEquals("/file/path", result.localUri)
        assertEquals(3, result.lastSeenVersion)
        assertEquals(5000L, result.lastSeenAt)
    }

    @Test
    fun `subscriptionDao updateLastSeen updates fields`() = runTest {
        subscriptionDao.upsert(SubscriptionEntity("s1", "pk", "f", null, null, 0, null, null, null))
        subscriptionDao.updateLastSeen("s1", 7, 8000L)
        val result = subscriptionDao.getById("s1")!!
        assertEquals(7, result.lastSeenVersion)
        assertEquals(8000L, result.lastSeenAt)
    }

    @Test
    fun `subscriptionDao updateLastNotified updates field`() = runTest {
        subscriptionDao.upsert(SubscriptionEntity("s1", "pk", "f", null, null, 0, null, null, null))
        subscriptionDao.updateLastNotified("s1", 9)
        val result = subscriptionDao.getById("s1")!!
        assertEquals(9, result.lastNotifiedVersion)
    }

    @Test
    fun `subscriptionDao getAll returns empty for empty table`() = runTest {
        assertTrue(subscriptionDao.getAll().isEmpty())
    }

    @Test
    fun `subscriptionDao handles null fields correctly`() = runTest {
        subscriptionDao.upsert(SubscriptionEntity("s1", "pk", null, null, null, 100, null, null, null))
        val result = subscriptionDao.getById("s1")!!
        assertNull(result.fileName)
        assertNull(result.localVersion)
        assertNull(result.localUri)
        assertNull(result.lastSeenVersion)
        assertNull(result.lastSeenAt)
        assertNull(result.lastNotifiedVersion)
    }

    @Test
    fun `subscriptionDao changeFlow emits on upsert`() = runTest {
        subscriptionDao.upsert(SubscriptionEntity("s1", "pk", "f", null, null, 0, null, null, null))
        advanceUntilIdle()
        assertTrue(subscriptionDao.changeFlow.value > 0)
    }

    @Test
    fun `subscriptionDao changeFlow emits on delete`() = runTest {
        subscriptionDao.upsert(SubscriptionEntity("s1", "pk", "f", null, null, 0, null, null, null))
        advanceUntilIdle()
        val before = subscriptionDao.changeFlow.value
        subscriptionDao.delete("s1")
        advanceUntilIdle()
        assertTrue(subscriptionDao.changeFlow.value > before)
    }

    @Test
    fun `appDatabase onUpgrade adds compressedSize column`() = runTest {
        val broadcast = BroadcastEntity("f1", "a", "t", "/p", "h", 100, 100, 1, "pk", null, "s", Role.ORIGINATOR, 0, 0)
        broadcastDao.upsert(broadcast)
        assertEquals(1, broadcastDao.getAll().size)

        db.onUpgrade(db.writableDatabase, 1, 2)

        // Data should be preserved after upgrade
        val all = broadcastDao.getAll()
        assertEquals(1, all.size)
        assertEquals(100, all[0].compressedSize)
    }

    @Test
    fun `broadcastDao multiple entities roundtrip`() = runTest {
        val b1 = BroadcastEntity("f1", "a.txt", "t", "/p1", "h1", 100, 100, 1, "pk1", null, "s1", Role.ORIGINATOR, 10, 20)
        val b2 = BroadcastEntity("f2", "b.txt", "t2", "/p2", "h2", 200, 200, 2, "pk2", "sk2", "s2", Role.RELAY, 30, 40)
        broadcastDao.upsert(b1)
        broadcastDao.upsert(b2)
        assertEquals(2, broadcastDao.getAll().size)

        broadcastDao.delete("f1")
        assertEquals(1, broadcastDao.getAll().size)
        assertEquals("f2", broadcastDao.getAll()[0].fileId)
    }

    @Test
    fun `subscriptionDao multiple entities roundtrip`() = runTest {
        val s1 = SubscriptionEntity("s1", "pk1", "f1", null, null, 0, null, null, null)
        val s2 = SubscriptionEntity("s2", "pk2", "f2", 1, "/p", 100, 2, 200, 1)
        subscriptionDao.upsert(s1)
        subscriptionDao.upsert(s2)
        assertEquals(2, subscriptionDao.getAll().size)

        subscriptionDao.delete("s1")
        assertEquals(1, subscriptionDao.getAll().size)
        assertEquals("s2", subscriptionDao.getAll()[0].fileId)
    }
}
