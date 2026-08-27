package p2p.broadcaster

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
    lateinit var wifiDirectService: WifiDirectService
        private set
    lateinit var syncEngine: SyncEngine
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase(this)
        broadcastDao = BroadcastDao(database)
        subscriptionDao = SubscriptionDao(database)
        cryptoService = CryptoService()
        fileService = FileService(this)
        notificationService = NotificationService(this)
        bleCentralService = BleCentralService(this)
        blePeripheralService = BlePeripheralService(this)
        wifiDirectService = WifiDirectService(this)
        syncEngine = SyncEngine(
            broadcastDao, subscriptionDao, cryptoService, fileService,
            bleCentralService, blePeripheralService, wifiDirectService, notificationService
        )
        syncEngine.start()
    }
}
