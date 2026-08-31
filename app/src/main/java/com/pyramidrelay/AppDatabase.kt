package com.pyramidrelay

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import android.database.Cursor

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class AppDatabase(context: android.content.Context) : SQLiteOpenHelper(context, "pyramidrelay.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE broadcasts (
                fileId TEXT PRIMARY KEY,
                fileName TEXT NOT NULL,
                relayName TEXT NOT NULL,
                mimeType TEXT NOT NULL,
                internalUri TEXT NOT NULL,
                fileHash TEXT NOT NULL,
                fileSize INTEGER NOT NULL,
                compressedSize INTEGER NOT NULL,
                version INTEGER NOT NULL,
                publicKey TEXT NOT NULL,
                privateKeyAlias TEXT,
                signature TEXT NOT NULL,
                role TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
        """)
        db.execSQL("""
            CREATE TABLE subscriptions (
                fileId TEXT PRIMARY KEY,
                publicKey TEXT NOT NULL,
                fileName TEXT,
                relayName TEXT,
                localVersion INTEGER,
                localUri TEXT,
                subscribedAt INTEGER NOT NULL,
                lastSeenVersion INTEGER,
                lastSeenAt INTEGER,
                lastNotifiedVersion INTEGER
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            val cursor = db.rawQuery("PRAGMA table_info(broadcasts)", null)
            val hasCompressedSize = cursor.use {
                val nameIndex = it.getColumnIndex("name")
                while (it.moveToNext()) {
                    if (nameIndex >= 0 && it.getString(nameIndex) == "compressedSize") return@use true
                }
                false
            }
            if (!hasCompressedSize) {
                db.execSQL("ALTER TABLE broadcasts ADD COLUMN compressedSize INTEGER NOT NULL DEFAULT 0")
            }
        }
        if (oldVersion < 3) {
            // Signature is retained as an empty compatibility column for existing databases.
            // New transfers use authenticated encryption instead.
        }
    }
}

class BroadcastDao(private val db: AppDatabase) {
    private val _changeFlow = MutableStateFlow(0L)
    val changeFlow: StateFlow<Long> = _changeFlow.asStateFlow()

    private fun notifyChange() { _changeFlow.value = System.currentTimeMillis() }

    suspend fun getAll(): List<BroadcastEntity> = withContext(Dispatchers.IO) {
        val cursor = db.readableDatabase.query("broadcasts", null, null, null, null, null, null)
        val list = mutableListOf<BroadcastEntity>()
        cursor.use {
            while (it.moveToNext()) list.add(cursorToEntity(it))
        }
        list
    }

    suspend fun getById(fileId: String): BroadcastEntity? = withContext(Dispatchers.IO) {
        val cursor = db.readableDatabase.query("broadcasts", null, "fileId=?", arrayOf(fileId), null, null, null)
        cursor.use {
            if (it.moveToFirst()) cursorToEntity(it) else null
        }
    }

    suspend fun upsert(broadcast: BroadcastEntity) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("fileId", broadcast.fileId)
            put("fileName", broadcast.fileName)
            put("relayName", broadcast.relayName)
            put("mimeType", broadcast.mimeType)
            put("internalUri", broadcast.internalUri)
            put("fileHash", broadcast.fileHash)
            put("fileSize", broadcast.fileSize)
            put("compressedSize", broadcast.compressedSize)
            put("version", broadcast.version)
            put("publicKey", broadcast.publicKey)
            put("privateKeyAlias", broadcast.privateKeyAlias)
            put("signature", broadcast.signature)
            put("role", broadcast.role.name)
            put("createdAt", broadcast.createdAt)
            put("updatedAt", broadcast.updatedAt)
        }
        db.writableDatabase.insertWithOnConflict("broadcasts", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        notifyChange()
    }

    suspend fun delete(fileId: String) = withContext(Dispatchers.IO) {
        db.writableDatabase.delete("broadcasts", "fileId=?", arrayOf(fileId))
        notifyChange()
    }

    suspend fun updateVersion(fileId: String, version: Int, fileHash: String, sig: String, uri: String, size: Long, compressedSize: Long, now: Long, fileName: String? = null) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("version", version); put("fileHash", fileHash); put("signature", sig)
            put("internalUri", uri); put("fileSize", size); put("compressedSize", compressedSize); put("updatedAt", now)
            if (fileName != null) put("fileName", fileName)
        }
        db.writableDatabase.update("broadcasts", cv, "fileId=?", arrayOf(fileId))
        notifyChange()
    }

    private fun cursorToEntity(c: Cursor): BroadcastEntity {
        val compressedSizeIdx = c.getColumnIndex("compressedSize")
        val compressedSize = if (compressedSizeIdx >= 0 && !c.isNull(compressedSizeIdx)) c.getLong(compressedSizeIdx) else c.getLong(c.getColumnIndexOrThrow("fileSize"))
        return BroadcastEntity(
            fileId = c.getString(c.getColumnIndexOrThrow("fileId")),
            fileName = c.getString(c.getColumnIndexOrThrow("fileName")),
            relayName = c.getString(c.getColumnIndexOrThrow("relayName")),
            mimeType = c.getString(c.getColumnIndexOrThrow("mimeType")),
            internalUri = c.getString(c.getColumnIndexOrThrow("internalUri")),
            fileHash = c.getString(c.getColumnIndexOrThrow("fileHash")),
            fileSize = c.getLong(c.getColumnIndexOrThrow("fileSize")),
            compressedSize = compressedSize,
            version = c.getInt(c.getColumnIndexOrThrow("version")),
            publicKey = c.getString(c.getColumnIndexOrThrow("publicKey")),
            privateKeyAlias = c.getString(c.getColumnIndexOrThrow("privateKeyAlias")),
            signature = c.getString(c.getColumnIndexOrThrow("signature")),
            role = Role.valueOf(c.getString(c.getColumnIndexOrThrow("role"))),
            createdAt = c.getLong(c.getColumnIndexOrThrow("createdAt")),
            updatedAt = c.getLong(c.getColumnIndexOrThrow("updatedAt"))
        )
    }
}

class SubscriptionDao(private val db: AppDatabase) {
    private val _changeFlow = MutableStateFlow(0L)
    val changeFlow: StateFlow<Long> = _changeFlow.asStateFlow()

    private fun notifyChange() { _changeFlow.value = System.currentTimeMillis() }

    suspend fun getAll(): List<SubscriptionEntity> = withContext(Dispatchers.IO) {
        val cursor = db.readableDatabase.query("subscriptions", null, null, null, null, null, null)
        val list = mutableListOf<SubscriptionEntity>()
        cursor.use { while (it.moveToNext()) list.add(cursorToEntity(it)) }
        list
    }

    suspend fun getById(fileId: String): SubscriptionEntity? = withContext(Dispatchers.IO) {
        val cursor = db.readableDatabase.query("subscriptions", null, "fileId=?", arrayOf(fileId), null, null, null)
        cursor.use { if (it.moveToFirst()) cursorToEntity(it) else null }
    }

    suspend fun upsert(subscription: SubscriptionEntity) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("fileId", subscription.fileId); put("publicKey", subscription.publicKey)
            put("fileName", subscription.fileName); put("relayName", subscription.relayName)
            put("localVersion", subscription.localVersion)
            put("localUri", subscription.localUri); put("subscribedAt", subscription.subscribedAt)
            put("lastSeenVersion", subscription.lastSeenVersion); put("lastSeenAt", subscription.lastSeenAt)
            put("lastNotifiedVersion", subscription.lastNotifiedVersion)
        }
        db.writableDatabase.insertWithOnConflict("subscriptions", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        notifyChange()
    }

    suspend fun delete(fileId: String) = withContext(Dispatchers.IO) {
        db.writableDatabase.delete("subscriptions", "fileId=?", arrayOf(fileId))
        notifyChange()
    }

    suspend fun updateReceived(fileId: String, localVersion: Int, localUri: String, lastSeen: Int, now: Long, fileName: String? = null) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("localVersion", localVersion); put("localUri", localUri)
            put("lastSeenVersion", lastSeen); put("lastSeenAt", now)
            if (fileName != null) put("fileName", fileName)
        }
        db.writableDatabase.update("subscriptions", cv, "fileId=?", arrayOf(fileId))
        notifyChange()
    }

    suspend fun updateLastSeen(fileId: String, version: Int, now: Long) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply { put("lastSeenVersion", version); put("lastSeenAt", now) }
        db.writableDatabase.update("subscriptions", cv, "fileId=?", arrayOf(fileId))
        notifyChange()
    }

    suspend fun updateLastNotified(fileId: String, version: Int) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply { put("lastNotifiedVersion", version) }
        db.writableDatabase.update("subscriptions", cv, "fileId=?", arrayOf(fileId))
        notifyChange()
    }

    private fun cursorToEntity(c: Cursor): SubscriptionEntity = SubscriptionEntity(
        fileId = c.getString(c.getColumnIndexOrThrow("fileId")),
        publicKey = c.getString(c.getColumnIndexOrThrow("publicKey")),
        fileName = c.getString(c.getColumnIndexOrThrow("fileName")),
        relayName = c.getString(c.getColumnIndexOrThrow("relayName")),
        localVersion = c.getInt(c.getColumnIndexOrThrow("localVersion")).let { if (c.isNull(c.getColumnIndexOrThrow("localVersion"))) null else it },
        localUri = c.getString(c.getColumnIndexOrThrow("localUri")),
        subscribedAt = c.getLong(c.getColumnIndexOrThrow("subscribedAt")),
        lastSeenVersion = c.getInt(c.getColumnIndexOrThrow("lastSeenVersion")).let { if (c.isNull(c.getColumnIndexOrThrow("lastSeenVersion"))) null else it },
        lastSeenAt = c.getLong(c.getColumnIndexOrThrow("lastSeenAt")).let { if (c.isNull(c.getColumnIndexOrThrow("lastSeenAt"))) null else it },
        lastNotifiedVersion = c.getInt(c.getColumnIndexOrThrow("lastNotifiedVersion")).let { if (c.isNull(c.getColumnIndexOrThrow("lastNotifiedVersion"))) null else it }
    )
}
