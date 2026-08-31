package com.pyramidrelay

import android.app.Application

class P2PBroadcasterApp : Application() {
    lateinit var database: AppDatabase
        private set
    lateinit var broadcastDao: BroadcastDao
        private set
    lateinit var subscriptionDao: SubscriptionDao
        private set
    lateinit var cryptoService: CryptoService
        private set
    lateinit var fileService: FileService
        private set
    lateinit var notificationService: NotificationService
        private set
    lateinit var bleCentralService: BleCentralService
        private set
    lateinit var blePeripheralService: BlePeripheralService
        private set
    lateinit var syncEngine: SyncEngine
        private set
    lateinit var settingsStore: SettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase(this)
        broadcastDao = BroadcastDao(database)
        subscriptionDao = SubscriptionDao(database)
        cryptoService = CryptoService(this)
        fileService = FileService(this)
        notificationService = NotificationService(this)
        bleCentralService = BleCentralService(this)
        val transferSemaphore = kotlinx.coroutines.sync.Semaphore(1) // Only one transfer at a time
        blePeripheralService = BlePeripheralService(this, transferSemaphore)
        syncEngine = SyncEngine(
            this, broadcastDao, subscriptionDao, cryptoService, fileService,
            bleCentralService, blePeripheralService, notificationService, transferSemaphore
        )
        settingsStore = SettingsStore(this)
        syncEngine.start()
    }
}
