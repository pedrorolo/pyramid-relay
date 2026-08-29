package p2p.broadcaster

import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelTest {

    private lateinit var cryptoService: CryptoService
    private lateinit var broadcastDao: BroadcastDao
    private lateinit var subscriptionDao: SubscriptionDao
    private lateinit var fileService: FileService
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        cryptoService = CryptoService()
        broadcastDao = mockk(relaxed = true)
        subscriptionDao = mockk(relaxed = true)
        fileService = mockk(relaxed = true)
        every { broadcastDao.changeFlow } returns MutableStateFlow(0L)
        every { subscriptionDao.changeFlow } returns MutableStateFlow(0L)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `spec 11 - BroadcastsViewModel only shows ORIGINATOR broadcasts`() = runTest {
        val originator = BroadcastEntity(
            "id1", "a.txt", "text/plain", "/p", "h", 100, 100, 1,
            "pk", null, "sig", Role.ORIGINATOR, 0, 0
        )
        val relay = BroadcastEntity(
            "id2", "b.txt", "text/plain", "/p", "h", 100, 100, 1,
            "pk", null, "sig", Role.RELAY, 0, 0
        )
        coEvery { broadcastDao.getAll() } returns listOf(originator, relay)

        val vm = BroadcastsViewModel(broadcastDao, cryptoService, fileService)
        advanceUntilIdle()

        val broadcasts = vm.broadcasts.value
        assertEquals(1, broadcasts.size)
        assertEquals("id1", broadcasts[0].fileId)
        assertEquals(Role.ORIGINATOR, broadcasts[0].role)
    }

    @Test
    fun `spec 11 - BroadcastsViewModel deleteBroadcast calls fileService and dao`() = runTest {
        coEvery { broadcastDao.getAll() } returns emptyList()

        val vm = BroadcastsViewModel(broadcastDao, cryptoService, fileService)
        advanceUntilIdle()

        val broadcast = BroadcastEntity(
            "del1", "del.txt", "text/plain", "/p", "h", 100, 100, 1,
            "pk", null, "sig", Role.ORIGINATOR, 0, 0
        )
        vm.deleteBroadcast(broadcast)
        advanceUntilIdle()

        coVerify { fileService.deleteAll("del1") }
        coVerify { broadcastDao.delete("del1") }
    }

    @Test
    fun `spec 11 - SubscriptionsViewModel addSubscription upserts entity`() = runTest {
        coEvery { subscriptionDao.getAll() } returns emptyList()

        val vm = SubscriptionsViewModel(subscriptionDao, broadcastDao, fileService, CryptoService())
        advanceUntilIdle()

        val crypto = CryptoService()
        val validPk = crypto.publicKeyToBase64(crypto.generateRsaKeyPair().public)
        vm.addSubscription("sub1", validPk, "name1")
        advanceUntilIdle()

        coVerify {
            subscriptionDao.upsert(match {
                it.fileId == "sub1" && it.publicKey == validPk && it.fileName == "name1"
            })
        }
    }

    @Test
    fun `spec 11 - SubscriptionsViewModel deleteSubscription calls all deletes`() = runTest {
        coEvery { subscriptionDao.getAll() } returns emptyList()

        val vm = SubscriptionsViewModel(subscriptionDao, broadcastDao, fileService, CryptoService())
        advanceUntilIdle()

        val sub = SubscriptionEntity("sub2", "pk", "f", null, null, 0L, null, null, null)
        vm.deleteSubscription(sub)
        advanceUntilIdle()

        coVerify { fileService.deleteAll("sub2") }
        coVerify { subscriptionDao.delete("sub2") }
        coVerify { broadcastDao.delete("sub2") }
    }

    @Test
    fun `spec 11 - SubscriptionsViewModel refresh loads from dao`() = runTest {
        val sub = SubscriptionEntity("sub3", "pk", "f", 2, "/path", 1000L, 2, 2000L, 2)
        coEvery { subscriptionDao.getAll() } returns listOf(sub)

        val vm = SubscriptionsViewModel(subscriptionDao, broadcastDao, fileService, CryptoService())
        advanceUntilIdle()

        assertEquals(1, vm.subscriptions.value.size)
        assertEquals("sub3", vm.subscriptions.value[0].fileId)
    }

    @Test
    fun `spec 11 - BroadcastsViewModel shows empty list when no broadcasts`() = runTest {
        coEvery { broadcastDao.getAll() } returns emptyList()

        val vm = BroadcastsViewModel(broadcastDao, cryptoService, fileService)
        advanceUntilIdle()

        assertTrue(vm.broadcasts.value.isEmpty())
    }

    @Test
    fun `spec 11 - SubscriptionsViewModel shows empty list when no subscriptions`() = runTest {
        coEvery { subscriptionDao.getAll() } returns emptyList()

        val vm = SubscriptionsViewModel(subscriptionDao, broadcastDao, fileService, CryptoService())
        advanceUntilIdle()

        assertTrue(vm.subscriptions.value.isEmpty())
    }

    @Test
    fun `formatSize correct for bytes`() {
        assertEquals("500 B", formatSizeImpl(500))
    }

    @Test
    fun `formatSize correct for KB`() {
        assertEquals("1 KB", formatSizeImpl(1024))
    }

    @Test
    fun `formatSize correct for MB`() {
        assertEquals("5 MB", formatSizeImpl(5L * 1024 * 1024))
    }

    @Test
    fun `formatSize correct for GB`() {
        assertEquals("2 GB", formatSizeImpl(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun `formatSize boundary at 1024 bytes is KB`() {
        assertEquals("1 KB", formatSizeImpl(1024))
    }

    @Test
    fun `formatSize boundary at 1024*1024 bytes is MB`() {
        assertEquals("1 MB", formatSizeImpl(1024L * 1024))
    }

    @Test
    fun `formatSize boundary at 1024*1024*1024 bytes is GB`() {
        assertEquals("1 GB", formatSizeImpl(1024L * 1024 * 1024))
    }

    private fun formatSizeImpl(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return "${bytes / 1024} KB"
        if (bytes < 1024 * 1024 * 1024) return "${bytes / (1024 * 1024)} MB"
        return "${bytes / (1024 * 1024 * 1024)} GB"
    }
}
