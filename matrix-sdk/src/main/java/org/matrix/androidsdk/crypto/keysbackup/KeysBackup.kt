/*
 * Copyright 2018 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.androidsdk.crypto.keysbackup

import android.support.annotation.UiThread
import android.support.annotation.VisibleForTesting
import android.support.annotation.WorkerThread
import org.matrix.androidsdk.MXSession
import org.matrix.androidsdk.crypto.MXCRYPTO_ALGORITHM_MEGOLM
import org.matrix.androidsdk.crypto.MXCRYPTO_ALGORITHM_MEGOLM_BACKUP
import org.matrix.androidsdk.crypto.MXCrypto
import org.matrix.androidsdk.crypto.MegolmSessionData
import org.matrix.androidsdk.crypto.data.ImportRoomKeysResult
import org.matrix.androidsdk.crypto.data.MXDeviceInfo
import org.matrix.androidsdk.crypto.data.MXOlmInboundGroupSession2
import org.matrix.androidsdk.crypto.util.computeRecoveryKey
import org.matrix.androidsdk.crypto.util.extractCurveKeyFromRecoveryKey
import org.matrix.androidsdk.rest.callback.ApiCallback
import org.matrix.androidsdk.rest.callback.SimpleApiCallback
import org.matrix.androidsdk.rest.callback.SuccessCallback
import org.matrix.androidsdk.rest.callback.SuccessErrorCallback
import org.matrix.androidsdk.rest.client.RoomKeysRestClient
import org.matrix.androidsdk.rest.model.MatrixError
import org.matrix.androidsdk.rest.model.keys.*
import org.matrix.androidsdk.util.JsonUtils
import org.matrix.androidsdk.util.Log
import org.matrix.olm.OlmException
import org.matrix.olm.OlmPkDecryption
import org.matrix.olm.OlmPkEncryption
import org.matrix.olm.OlmPkMessage
import java.security.InvalidParameterException
import java.util.*

/**
 * A KeysBackup class instance manage incremental backup of e2e keys (megolm keys)
 * to the user's homeserver.
 */
class KeysBackup(private val mCrypto: MXCrypto, session: MXSession) {

    private val mRoomKeysRestClient = session.roomKeysRestClient

    private val mKeysBackupStateManager = KeysBackupStateManager(mCrypto)

    // The backup version being used.
    private var mKeysBackupVersion: KeysVersionResult? = null

    // The backup key being used.
    private var mBackupKey: OlmPkEncryption? = null

    private val mRandom = Random()

    private var backupAllGroupSessionsCallback: ApiCallback<Void?>? = null

    private var mKeysBackupStateListener: KeysBackupStateManager.KeysBackupStateListener? = null

    val isEnabled: Boolean
        get() = mKeysBackupStateManager.isEnabled

    val state: KeysBackupStateManager.KeysBackupState
        get() = mKeysBackupStateManager.state

    val currentBackupVersion: String?
        get() = mKeysBackupVersion?.version

    fun addListener(listener: KeysBackupStateManager.KeysBackupStateListener) {
        mKeysBackupStateManager.addListener(listener)
    }

    fun removeListener(listener: KeysBackupStateManager.KeysBackupStateListener) {
        mKeysBackupStateManager.removeListener(listener)
    }

    /**
     * Set up the data required to create a new backup version.
     * The backup version will not be created and enabled until [createKeysBackupVersion]
     * is called.
     * The returned [MegolmBackupCreationInfo] object has a `recoveryKey` member with
     * the user-facing recovery key string.
     *
     * @param password an optional passphrase string that can be entered by the user
     * when restoring the backup as an alternative to entering the recovery key.
     * @param callback Asynchronous callback
     */
    fun prepareKeysBackupVersion(password: String?,
                                 callback: SuccessErrorCallback<MegolmBackupCreationInfo>) {
        mCrypto.decryptingThreadHandler.post {
            try {
                val olmPkDecryption = OlmPkDecryption()
                val megolmBackupAuthData = MegolmBackupAuthData()

                if (password != null) {
                    // Generate a private key from the password
                    val generatePrivateKeyResult = generatePrivateKeyWithPassword(password)
                    megolmBackupAuthData.publicKey = olmPkDecryption.setPrivateKey(generatePrivateKeyResult.privateKey)
                    megolmBackupAuthData.privateKeySalt = generatePrivateKeyResult.salt
                    megolmBackupAuthData.privateKeyIterations = generatePrivateKeyResult.iterations
                } else {
                    val publicKey = olmPkDecryption.generateKey()

                    megolmBackupAuthData.publicKey = publicKey
                }

                megolmBackupAuthData.signatures = mCrypto.signObject(JsonUtils.getCanonicalizedJsonString(megolmBackupAuthData.signalableJSONDictionary()))


                val megolmBackupCreationInfo = MegolmBackupCreationInfo()
                megolmBackupCreationInfo.algorithm = MXCRYPTO_ALGORITHM_MEGOLM_BACKUP
                megolmBackupCreationInfo.authData = megolmBackupAuthData
                megolmBackupCreationInfo.recoveryKey = computeRecoveryKey(olmPkDecryption.privateKey())

                mCrypto.uiHandler.post { callback.onSuccess(megolmBackupCreationInfo) }
            } catch (e: OlmException) {
                Log.e(LOG_TAG, "OlmException: ", e)

                mCrypto.uiHandler.post { callback.onUnexpectedError(e) }
            }
        }
    }

    /**
     * Create a new keys backup version and enable it, using the information return from [prepareKeysBackupVersion].
     *
     * @param keysBackupCreationInfo the info object from [prepareKeysBackupVersion].
     * @param callback               Asynchronous callback
     */
    fun createKeysBackupVersion(keysBackupCreationInfo: MegolmBackupCreationInfo,
                                callback: ApiCallback<KeysVersion>) {
        val createKeysBackupVersionBody = CreateKeysBackupVersionBody()
        createKeysBackupVersionBody.algorithm = keysBackupCreationInfo.algorithm
        createKeysBackupVersionBody.authData = JsonUtils.getBasicGson().toJsonTree(keysBackupCreationInfo.authData)

        mKeysBackupStateManager.state = KeysBackupStateManager.KeysBackupState.Enabling

        mRoomKeysRestClient.createKeysBackupVersion(createKeysBackupVersionBody, object : ApiCallback<KeysVersion> {
            override fun onSuccess(info: KeysVersion) {
                // Reset backup markers.
                mCrypto.cryptoStore.resetBackupMarkers()

                val keyBackupVersion = KeysVersionResult()
                keyBackupVersion.algorithm = createKeysBackupVersionBody.algorithm
                keyBackupVersion.authData = createKeysBackupVersionBody.authData
                keyBackupVersion.version = info.version

                enableKeysBackup(keyBackupVersion)

                callback.onSuccess(info)
            }

            override fun onUnexpectedError(e: java.lang.Exception?) {
                mKeysBackupStateManager.state = KeysBackupStateManager.KeysBackupState.Disabled
                callback.onUnexpectedError(e)
            }

            override fun onNetworkError(e: java.lang.Exception?) {
                mKeysBackupStateManager.state = KeysBackupStateManager.KeysBackupState.Disabled
                callback.onNetworkError(e)
            }

            override fun onMatrixError(e: MatrixError?) {
                mKeysBackupStateManager.state = KeysBackupStateManager.KeysBackupState.Disabled
                callback.onMatrixError(e)
            }
        })
    }

    /**
     * Delete a keys backup version. It will delete all backed up keys on the server.
     * If we are backing up to this version. Backup will be stopped.
     *
     * @param version  the backup version to delete.
     * @param callback Asynchronous callback
     */
    fun deleteKeysFromBackup(version: String, callback: ApiCallback<Void>) {
        mCrypto.decryptingThreadHandler.post {
            // If we're currently backing up to this backup... stop.
            // (We start using it automatically in createKeysBackupVersion so this is symmetrical).
            if (mKeysBackupVersion != null && version == mKeysBackupVersion!!.version) {
                disableKeysBackup()
                mKeysBackupStateManager.state = KeysBackupStateManager.KeysBackupState.Unknown
            }

            mCrypto.uiHandler.post { mRoomKeysRestClient.deleteKeys(version, callback) }
        }
    }

    /**
     * Start to back up keys immediately.
     *
     * @param progress the callback to follow the progress
     * @param callback the main callback
     */
    fun backupAllGroupSessions(progress: BackupProgressListener?,
                               callback: ApiCallback<Void?>?) {
        // Get a status right now
        getBackupProgress(object : BackupProgressListener {
            override fun onProgress(backedUp: Int, total: Int) {
                // Reset previous listeners if any
                resetBackupAllGroupSessionsListeners()
                Log.d(LOG_TAG, "backupAllGroupSessions: backupProgress: $backedUp/$total")
                progress?.onProgress(backedUp, total)

                if (backedUp == total) {
                    Log.d(LOG_TAG, "backupAllGroupSessions: complete")
                    callback?.onSuccess(null)
                    return
                }

                backupAllGroupSessionsCallback = callback

                // Listen to `state` change to determine when to call onBackupProgress and onComplete
                mKeysBackupStateListener = object : KeysBackupStateManager.KeysBackupStateListener {
                    override fun onStateChange(newState: KeysBackupStateManager.KeysBackupState) {
                        getBackupProgress(object : BackupProgressListener {
                            override fun onProgress(backedUp: Int, total: Int) {
                                progress?.onProgress(backedUp, total)

                                // If backup is finished, notify the main listener
                                if (state === KeysBackupStateManager.KeysBackupState.ReadyToBackUp) {
                                    backupAllGroupSessionsCallback?.onSuccess(null)
                                    resetBackupAllGroupSessionsListeners()
                                }
                            }
                        })
                    }
                }

                mKeysBackupStateManager.addListener(mKeysBackupStateListener!!)

                backupKeys()
            }
        })
    }

    /**
     * Check trust on a key backup version.
     *
     * @param keysBackupVersion the backup version to check.
     * @param callback block called when the operations completes.
     */
    fun getKeysBackupTrust(keysBackupVersion: KeysVersionResult,
                           callback: SuccessCallback<KeysBackupVersionTrust>) {
        mCrypto.decryptingThreadHandler.post {
            val myUserId = mCrypto.myDevice.userId

            val keysBackupVersionTrust = KeysBackupVersionTrust()
            val authData = keysBackupVersion.getAuthDataAsMegolmBackupAuthData()

            if (keysBackupVersion.algorithm == null
                    || authData == null
                    || authData.publicKey.isEmpty()
                    || authData.signatures?.isEmpty() == true) {
                Log.d(LOG_TAG, "getKeysBackupTrust: Key backup is absent or missing required data")
                mCrypto.uiHandler.post { callback.onSuccess(keysBackupVersionTrust) }
                return@post
            }

            val mySigs: Map<String, *> = authData.signatures!![myUserId] as Map<String, *>
            if (mySigs.isEmpty()) {
                Log.d(LOG_TAG, "getKeysBackupTrust: Ignoring key backup because it lacks any signatures from this user")
                mCrypto.uiHandler.post { callback.onSuccess(keysBackupVersionTrust) }
                return@post
            }

            for (keyId in mySigs.keys) {
                // XXX: is this how we're supposed to get the device id?
                var deviceId: String? = null
                val components = keyId.split(":")
                if (components.size == 2) {
                    deviceId = components[1]
                }

                var device: MXDeviceInfo? = null
                if (deviceId != null) {
                    device = mCrypto.cryptoStore.getUserDevice(deviceId, myUserId)

                    var isSignatureValid = false

                    if (device == null) {
                        Log.d(LOG_TAG, "getKeysBackupTrust: Signature from unknown device $deviceId")
                    } else {
                        mCrypto.olmDevice?.let {
                            try {
                                it.verifySignature(device.fingerprint(), authData.signalableJSONDictionary(), mySigs[keyId] as String)
                                isSignatureValid = true
                            } catch (e: OlmException) {
                                Log.d(LOG_TAG, "getKeysBackupTrust: Bad signature from device " + device.deviceId + " " + e.localizedMessage)
                            }
                        }

                        if (isSignatureValid && device.isVerified) {
                            keysBackupVersionTrust.usable = true
                        }
                    }

                    val signature = KeysBackupVersionTrustSignature()
                    signature.device = device
                    signature.valid = isSignatureValid
                    signature.deviceId = deviceId
                    keysBackupVersionTrust.signatures.add(signature)
                }
            }

            mCrypto.uiHandler.post { callback.onSuccess(keysBackupVersionTrust) }
        }
    }

    private fun resetBackupAllGroupSessionsListeners() {
        backupAllGroupSessionsCallback = null

        mKeysBackupStateListener?.let {
            mKeysBackupStateManager.removeListener(it)
        }

        mKeysBackupStateListener = null
    }

    interface BackupProgressListener {
        fun onProgress(backedUp: Int, total: Int)
    }

    private fun getBackupProgress(listener: BackupProgressListener) {
        mCrypto.decryptingThreadHandler.post {
            val backedUpKeys = mCrypto.cryptoStore.inboundGroupSessionsCount(true)
            val total = mCrypto.cryptoStore.inboundGroupSessionsCount(false)

            mCrypto.uiHandler.post { listener.onProgress(backedUpKeys, total) }
        }
    }

    /**
     * Restore a backup with a recovery key from a given backup version stored on the homeserver.
     *
     * @param version     the backup version to restore from.
     * @param recoveryKey the recovery key to decrypt the retrieved backup.
     * @param roomId      the id of the room to get backup data from.
     * @param sessionId   the id of the session to restore.
     * @param callback    Callback. It provides the number of found keys and the number of successfully imported keys.
     */
    fun restoreKeysWithRecoveryKey(version: String,
                                   recoveryKey: String,
                                   roomId: String?,
                                   sessionId: String?,
                                   callback: ApiCallback<ImportRoomKeysResult>) {
        Log.d(LOG_TAG, "restoreKeysWithRecoveryKey: From backup version: $version")

        mCrypto.decryptingThreadHandler.post(Runnable {
            // Get a PK decryption instance
            val decryption = pkDecryptionFromRecoveryKey(recoveryKey)
            if (decryption == null) {
                Log.e(LOG_TAG, "restoreKeysWithRecoveryKey: Invalid recovery key. Error")
                mCrypto.uiHandler.post { callback.onUnexpectedError(InvalidParameterException("Invalid recovery key")) }
                return@Runnable
            }

            // Get backed up keys from the homeserver
            getKeys(sessionId, roomId, version, object : ApiCallback<KeysBackupData> {
                override fun onUnexpectedError(e: Exception) {
                    mCrypto.uiHandler.post { callback.onUnexpectedError(e) }
                }

                override fun onNetworkError(e: Exception) {
                    mCrypto.uiHandler.post { callback.onNetworkError(e) }
                }

                override fun onMatrixError(e: MatrixError) {
                    mCrypto.uiHandler.post { callback.onMatrixError(e) }
                }

                override fun onSuccess(keysBackupData: KeysBackupData) {
                    val sessionsData = ArrayList<MegolmSessionData>()
                    // Restore that data
                    var sessionsFromHsCount = 0
                    for (roomIdLoop in keysBackupData.roomIdToRoomKeysBackupData.keys) {
                        for (sessionIdLoop in keysBackupData.roomIdToRoomKeysBackupData[roomIdLoop]!!.sessionIdToKeyBackupData.keys) {
                            sessionsFromHsCount++

                            val keyBackupData = keysBackupData.roomIdToRoomKeysBackupData[roomIdLoop]!!.sessionIdToKeyBackupData[sessionIdLoop]!!

                            val sessionData = decryptKeyBackupData(keyBackupData, sessionIdLoop, roomIdLoop, decryption)

                            sessionData?.let {
                                sessionsData.add(it)
                            }
                        }
                    }
                    Log.d(LOG_TAG, "restoreKeysWithRecoveryKey: Decrypted " + sessionsData.size + " keys out of "
                            + sessionsFromHsCount + " from the backup store on the homeserver")

                    if (sessionsFromHsCount > 0 && sessionsData.size == 0) {
                        // If we fail to decrypt all sessions, we have a credential problem
                        Log.e(LOG_TAG, "[MXKeyBackup] restoreKeyBackup: Invalid recovery key or password")
                        onUnexpectedError(InvalidParameterException("Invalid recovery key or password"))
                        return
                    }

                    // Do not trigger a backup for them if they come from the backup version we are using
                    val backUp = version != mKeysBackupVersion?.version
                    if (backUp) {
                        Log.d(LOG_TAG, "restoreKeysWithRecoveryKey: Those keys will be backed up to backup version: " + mKeysBackupVersion?.version)
                    }

                    // Import them into the crypto store
                    mCrypto.importMegolmSessionsData(sessionsData, backUp, callback)
                }
            })
        })
    }

    /**
     * Restore a backup with a password from a given backup version stored on the homeserver.
     *
     * @param version the backup version to restore from.
     * @param password the password to decrypt the retrieved backup.
     * @param roomId the id of the room to get backup data from.
     * @param sessionId the id of the session to restore.
     * @param callback Callback. It provides the number of found keys and the number of successfully imported keys.
     */
    fun restoreKeyBackupWithPassword(version: String,
                                     password: String,
                                     roomId: String?,
                                     sessionId: String?,
                                     callback: ApiCallback<ImportRoomKeysResult>) {
        Log.d(LOG_TAG, "[MXKeyBackup] restoreKeyBackup with password: From backup version: $version")

        // Fetch authentication info about this version
        // to retrieve the private key from the password
        getVersion(version, object : SimpleApiCallback<KeysVersionResult>(callback) {
            override fun onSuccess(info: KeysVersionResult) {
                val megolmBackupAuthData = info.getAuthDataAsMegolmBackupAuthData()

                if (megolmBackupAuthData.privateKeySalt == null || megolmBackupAuthData.privateKeyIterations == null) {
                    callback.onUnexpectedError(IllegalStateException("Salt and/or iterations not found: this backup cannot be restored with a password"))
                    return
                }

                // This is the recovery key
                val privateKey = retrievePrivateKeyWithPassword(password,
                        megolmBackupAuthData.privateKeySalt!!,
                        megolmBackupAuthData.privateKeyIterations!!)

                val recoveryKey = computeRecoveryKey(privateKey)

                restoreKeysWithRecoveryKey(version, recoveryKey, roomId, sessionId, callback)
            }
        })
    }

    /**
     * Same method as [RoomKeysRestClient.getRoomKey] except that it accepts nullable
     * parameters and always returns a KeysBackupData object through the Callback
     */
    private fun getKeys(sessionId: String?,
                        roomId: String?,
                        version: String,
                        callback: ApiCallback<KeysBackupData>) {
        if (roomId != null && sessionId != null) {
            // Get key for the room and for the session
            mRoomKeysRestClient.getRoomKey(roomId, sessionId, version, object : SimpleApiCallback<KeyBackupData>(callback) {
                override fun onSuccess(info: KeyBackupData) {
                    // Convert to KeysBackupData
                    val keysBackupData = KeysBackupData()
                    keysBackupData.roomIdToRoomKeysBackupData = HashMap()
                    val roomKeysBackupData = RoomKeysBackupData()
                    roomKeysBackupData.sessionIdToKeyBackupData = HashMap()
                    roomKeysBackupData.sessionIdToKeyBackupData[sessionId] = info
                    keysBackupData.roomIdToRoomKeysBackupData[roomId] = roomKeysBackupData

                    callback.onSuccess(keysBackupData)
                }
            })
        } else if (roomId != null) {
            // Get all keys for the room
            mRoomKeysRestClient.getRoomKeys(roomId, version, object : SimpleApiCallback<RoomKeysBackupData>(callback) {
                override fun onSuccess(info: RoomKeysBackupData) {
                    // Convert to KeysBackupData
                    val keysBackupData = KeysBackupData()
                    keysBackupData.roomIdToRoomKeysBackupData = HashMap()
                    keysBackupData.roomIdToRoomKeysBackupData[roomId] = info

                    callback.onSuccess(keysBackupData)
                }
            })
        } else {
            // Get all keys
            mRoomKeysRestClient.getKeys(version, callback)
        }
    }

    @VisibleForTesting
    @WorkerThread
    fun pkDecryptionFromRecoveryKey(recoveryKey: String): OlmPkDecryption? {
        // Extract the primary key
        val privateKey = extractCurveKeyFromRecoveryKey(recoveryKey)

        // Built the PK decryption with it
        var decryption: OlmPkDecryption? = null
        if (privateKey != null) {
            try {
                decryption = OlmPkDecryption()
                decryption.setPrivateKey(privateKey)
            } catch (e: OlmException) {
                Log.e(LOG_TAG, "OlmException", e)
            }

        }

        return decryption
    }

    /**
     * Do a backup if there are new keys, with a delay
     */
    fun maybeBackupKeys() {
        when (state) {
            KeysBackupStateManager.KeysBackupState.Unknown -> {
                // If not already done, check for a valid backup version on the homeserver.
                // If one, maybeBackupKeys will be called again.
                checkAndStartKeysBackup()
            }
            KeysBackupStateManager.KeysBackupState.ReadyToBackUp -> {
                mKeysBackupStateManager.state = KeysBackupStateManager.KeysBackupState.WillBackUp

                // Wait between 0 and 10 seconds, to avoid backup requests from
                // different clients hitting the server all at the same time when a
                // new key is sent
                val delayInMs = mRandom.nextInt(KEY_BACKUP_WAITING_TIME_TO_SEND_KEY_BACKUP_MILLIS).toLong()

                mCrypto.uiHandler.postDelayed({ backupKeys() }, delayInMs)
            }
            else -> {
                Log.d(LOG_TAG, "maybeBackupKeys: Skip it because state: $state")
            }
        }
    }

    /**
     * Get information about a backup version defined on the homeserver.
     *
     * It can be different than mKeysBackupVersion.
     * @param version the backup version
     * @param callback
     */
    fun getVersion(version: String,
                   callback: ApiCallback<KeysVersionResult?>) {
        mRoomKeysRestClient.getKeysBackupVersion(version, object : SimpleApiCallback<KeysVersionResult>(callback) {
            override fun onSuccess(info: KeysVersionResult) {
                callback.onSuccess(info)
            }

            override fun onMatrixError(e: MatrixError) {
                // Workaround because the homeserver currently returns M_NOT_FOUND when there is no key backup
                if (e.errcode == MatrixError.NOT_FOUND) {
                    callback.onSuccess(null)
                } else {
                    // Transmit the error
                    callback.onMatrixError(e)
                }
            }
        })
    }

    /**
     * Retrieve the current version of the backup from the home server
     *
     * It can be different than mKeysBackupVersion.
     * @param callback onSuccess(null) will be called if there is no backup on the server
     */
    fun getCurrentVersion(callback: ApiCallback<KeysVersionResult?>) {
        mRoomKeysRestClient.getKeysBackupLastVersion(object : SimpleApiCallback<KeysVersionResult>(callback) {
            override fun onSuccess(info: KeysVersionResult) {
                callback.onSuccess(info)
            }

            override fun onMatrixError(e: MatrixError) {
                // Workaround because the homeserver currently returns M_NOT_FOUND when there is no key backup
                if (e.errcode == MatrixError.NOT_FOUND) {
                    callback.onSuccess(null)
                } else {
                    // Transmit the error
                    callback.onMatrixError(e)
                }
            }
        })
    }

    /**
     * Check the server for an active key backup.
     *
     * If one is present and has a valid signature from one of the user's verified
     * devices, start backing up to it.
     */
    fun checkAndStartKeysBackup() {
        if (isEnabled) {
            Log.w(LOG_TAG, "checkAndStartKeysBackup: invalid state: $state")

            return
        }

        mKeysBackupStateManager.state = KeysBackupStateManager.KeysBackupState.CheckingBackUpOnHomeserver

        getCurrentVersion(object : ApiCallback<KeysVersionResult?> {
            override fun onSuccess(keyBackupVersion: KeysVersionResult?) {
                if (keyBackupVersion == null) {
                    Log.d(LOG_TAG, "checkAndStartKeysBackup: Found no key backup version on the homeserver")
                    disableKeysBackup()
                    mKeysBackupStateManager.state = KeysBackupStateManager.KeysBackupState.Disabled
                } else {
                    getKeysBackupTrust(keyBackupVersion, SuccessCallback { trustInfo ->
                        if (trustInfo.usable) {
                            Log.d(LOG_TAG, "checkAndStartKeysBackup: Found usable key backup. version: " + keyBackupVersion.version)
                            when {
                                mKeysBackupVersion == null -> {
                                    // Check the version we used at the previous app run
                                    val versionInStore = mCrypto.cryptoStore.keyBackupVersion
                                    if (versionInStore != null && versionInStore != keyBackupVersion.version) {
                                        Log.d(LOG_TAG, " -> clean the previously used version $versionInStore")
                                        disableKeysBackup()
                                    }

                                    Log.d(LOG_TAG, "   -> enabling key backups")
                                    enableKeysBackup(keyBackupVersion)
                                }
                                mKeysBackupVersion!!.version.equals(keyBackupVersion.version) -> {
                                    Log.d(LOG_TAG, "   -> same backup version (" + keyBackupVersion.version + "). Keep using it")
                                    mKeysBackupStateManager.state = KeysBackupStateManager.KeysBackupState.ReadyToBackUp
                                }
                                else -> {
                                    Log.d(LOG_TAG, "   -> disable the current version (" + mKeysBackupVersion!!.version
                                            + ") and enabling the new one: " + keyBackupVersion.version)
                                    disableKeysBackup()
                                    enableKeysBackup(keyBackupVersion)
                                }
                            }
                        } else {
                            Log.d(LOG_TAG, "checkAndStartKeysBackup: No usable key backup. version: " + keyBackupVersion.version)
                            if (mKeysBackupVersion == null) {
                                Log.d(LOG_TAG, "   -> not enabling key backup")
                            } else {
                                Log.d(LOG_TAG, "   -> disabling key backup")
                                disableKeysBackup()
                            }

                            mKeysBackupStateManager.state = KeysBackupStateManager.KeysBackupState.NotTrusted
                        }
                    })
                }
            }

            override fun onUnexpectedError(e: Exception?) {
                Log.e(LOG_TAG, "checkAndStartKeysBackup: Failed to get current version", e)
                mKeysBackupStateManager.state = KeysBackupStateManager.KeysBackupState.Unknown
            }

            override fun onNetworkError(e: Exception?) {
                Log.e(LOG_TAG, "checkAndStartKeysBackup: Failed to get current version", e)
                mKeysBackupStateManager.state = KeysBackupStateManager.KeysBackupState.Unknown
            }

            override fun onMatrixError(e: MatrixError?) {
                Log.e(LOG_TAG, "checkAndStartKeysBackup: Failed to get current version " + e?.localizedMessage)
                mKeysBackupStateManager.state = KeysBackupStateManager.KeysBackupState.Unknown
            }
        })
    }

    /* ==========================================================================================
     * Private
     * ========================================================================================== */

    /**
     * Enable backing up of keys.
     * This method will update the state and will start sending keys in nominal case
     *
     * @param keysVersionResult backup information object as returned by [getCurrentVersion].
     */
    private fun enableKeysBackup(keysVersionResult: KeysVersionResult) {
        if (keysVersionResult.authData != null) {
            val retrievedMegolmBackupAuthData = keysVersionResult.getAuthDataAsMegolmBackupAuthData()

            if (retrievedMegolmBackupAuthData != null) {
                mKeysBackupVersion = keysVersionResult
                mCrypto.cryptoStore.keyBackupVersion = keysVersionResult.version

                try {
                    mBackupKey = OlmPkEncryption().apply {
                        setRecipientKey(retrievedMegolmBackupAuthData.publicKey)
                    }
                } catch (e: OlmException) {
                    Log.e(LOG_TAG, "OlmException", e)
                    mKeysBackupStateManager.state = KeysBackupStateManager.KeysBackupState.Disabled
                    return
                }

                mKeysBackupStateManager.state = KeysBackupStateManager.KeysBackupState.ReadyToBackUp

                maybeBackupKeys()
            } else {
                Log.e(LOG_TAG, "Invalid authentication data")
                mKeysBackupStateManager.state = KeysBackupStateManager.KeysBackupState.Disabled
            }
        } else {
            Log.e(LOG_TAG, "Invalid authentication data")
            mKeysBackupStateManager.state = KeysBackupStateManager.KeysBackupState.Disabled
        }
    }

    /**
     * Disable backing up of keys.
     * Note: This method does not update the state
     */
    private fun disableKeysBackup() {
        resetBackupAllGroupSessionsListeners()

        mKeysBackupVersion = null
        mCrypto.cryptoStore.keyBackupVersion = null
        mBackupKey = null

        // Reset backup markers
        mCrypto.cryptoStore.resetBackupMarkers()
    }

    /**
     * Send a chunk of keys to backup
     */
    @UiThread
    private fun backupKeys() {
        Log.d(LOG_TAG, "backupKeys")

        // Sanity check
        if (!isEnabled || mBackupKey == null || mKeysBackupVersion == null) {
            Log.d(LOG_TAG, "backupKeys: Invalid configuration")
            backupAllGroupSessionsCallback?.onUnexpectedError(IllegalStateException("Invalid configuration"))
            resetBackupAllGroupSessionsListeners()

            return
        }

        if (state === KeysBackupStateManager.KeysBackupState.BackingUp) {
            // Do nothing if we are already backing up
            Log.d(LOG_TAG, "backupKeys: Invalid state: $state")
            return
        }

        // Get a chunk of keys to backup
        val sessions = mCrypto.cryptoStore.inboundGroupSessionsToBackup(KEY_BACKUP_SEND_KEYS_MAX_COUNT)

        Log.d(LOG_TAG, "backupKeys: 1 - " + sessions.size + " sessions to back up")

        if (sessions.isEmpty()) {
            // Backup is up to date
            mKeysBackupStateManager.state = KeysBackupStateManager.KeysBackupState.ReadyToBackUp

            backupAllGroupSessionsCallback?.onSuccess(null)
            resetBackupAllGroupSessionsListeners()
            return
        }

        mKeysBackupStateManager.state = KeysBackupStateManager.KeysBackupState.BackingUp

        mCrypto.encryptingThreadHandler.post {
            Log.d(LOG_TAG, "backupKeys: 2 - Encrypting keys")

            // Gather data to send to the homeserver
            // roomId -> sessionId -> MXKeyBackupData
            val keysBackupData = KeysBackupData()
            keysBackupData.roomIdToRoomKeysBackupData = HashMap()

            for (session in sessions) {
                val keyBackupData = encryptGroupSession(session)
                if (keysBackupData.roomIdToRoomKeysBackupData[session.mRoomId] == null) {
                    val roomKeysBackupData = RoomKeysBackupData()
                    roomKeysBackupData.sessionIdToKeyBackupData = HashMap()
                    keysBackupData.roomIdToRoomKeysBackupData[session.mRoomId] = roomKeysBackupData
                }

                try {
                    keysBackupData.roomIdToRoomKeysBackupData[session.mRoomId]!!.sessionIdToKeyBackupData[session.mSession.sessionIdentifier()] = keyBackupData
                } catch (e: OlmException) {
                    Log.e(LOG_TAG, "OlmException", e)
                }
            }

            Log.d(LOG_TAG, "backupKeys: 4 - Sending request")

            // Make the request
            mRoomKeysRestClient.backupKeys(mKeysBackupVersion!!.version!!, keysBackupData, object : ApiCallback<Void> {
                override fun onNetworkError(e: Exception) {
                    mCrypto.uiHandler.post {
                        backupAllGroupSessionsCallback?.onNetworkError(e)
                        resetBackupAllGroupSessionsListeners()

                        onError()
                    }
                }

                private fun onError() {
                    Log.e(LOG_TAG, "backupKeys: backupKeys failed.")

                    // Retry a bit later
                    mKeysBackupStateManager.state = KeysBackupStateManager.KeysBackupState.ReadyToBackUp
                    maybeBackupKeys()
                }

                override fun onMatrixError(e: MatrixError) {
                    mCrypto.uiHandler.post {
                        Log.e(LOG_TAG, "backupKeys: backupKeys failed. Error: " + e.localizedMessage)

                        if (e.errcode == MatrixError.WRONG_ROOM_KEYS_VERSION) {
                            mKeysBackupStateManager.state = KeysBackupStateManager.KeysBackupState.WrongBackUpVersion
                            backupAllGroupSessionsCallback?.onMatrixError(e)
                            resetBackupAllGroupSessionsListeners()
                            disableKeysBackup()

                            // disableKeysBackup() set state to Disable, so ensure it is WrongBackUpVersion
                            mKeysBackupStateManager.state = KeysBackupStateManager.KeysBackupState.WrongBackUpVersion
                        } else {
                            // Come back to the ready state so that we will retry on the next received key
                            mKeysBackupStateManager.state = KeysBackupStateManager.KeysBackupState.ReadyToBackUp
                        }
                    }
                }

                override fun onUnexpectedError(e: Exception) {
                    mCrypto.uiHandler.post {
                        backupAllGroupSessionsCallback?.onUnexpectedError(e)
                        resetBackupAllGroupSessionsListeners()

                        onError()
                    }
                }

                override fun onSuccess(info: Void?) {
                    mCrypto.uiHandler.post {
                        Log.d(LOG_TAG, "backupKeys: 5a - Request complete")

                        // Mark keys as backed up
                        for (session in sessions) {
                            try {
                                mCrypto.cryptoStore.markBackupDoneForInboundGroupSessionWithId(session.mSession.sessionIdentifier(), session.mSenderKey)
                            } catch (e: OlmException) {
                                Log.e(LOG_TAG, "OlmException", e)
                            }
                        }

                        if (sessions.size < KEY_BACKUP_SEND_KEYS_MAX_COUNT) {
                            Log.d(LOG_TAG, "backupKeys: All keys have been backed up")
                            // Note: Changing state will trigger the call to backupAllGroupSessionsCallback.onSuccess()
                            mKeysBackupStateManager.state = KeysBackupStateManager.KeysBackupState.ReadyToBackUp
                        } else {
                            Log.d(LOG_TAG, "backupKeys: Continue to back up keys")
                            mKeysBackupStateManager.state = KeysBackupStateManager.KeysBackupState.WillBackUp

                            backupKeys()
                        }
                    }
                }
            })
        }
    }

    @VisibleForTesting
    @WorkerThread
    fun encryptGroupSession(session: MXOlmInboundGroupSession2): KeyBackupData {
        // Gather information for each key
        val device = mCrypto.deviceWithIdentityKey(session.mSenderKey, MXCRYPTO_ALGORITHM_MEGOLM)

        // Build the m.megolm_backup.v1.curve25519-aes-sha2 data as defined at
        // https://github.com/uhoreg/matrix-doc/blob/e2e_backup/proposals/1219-storing-megolm-keys-serverside.md#mmegolm_backupv1curve25519-aes-sha2-key-format
        val sessionData = session.exportKeys()
        val sessionBackupData = mapOf(
                "algorithm" to sessionData!!.algorithm,
                "sender_key" to sessionData.senderKey,
                "sender_claimed_keys" to sessionData.senderClaimedKeys,
                "forwarding_curve25519_key_chain" to (sessionData.forwardingCurve25519KeyChain ?: ArrayList<Any>()),
                "session_key" to sessionData.sessionKey)

        var encryptedSessionBackupData: OlmPkMessage? = null
        try {
            encryptedSessionBackupData = mBackupKey?.encrypt(JsonUtils.getGson(false).toJson(sessionBackupData))
        } catch (e: OlmException) {
            Log.e(LOG_TAG, "OlmException", e)
        }

        // Build backup data for that key
        val keyBackupData = KeyBackupData()
        try {
            keyBackupData.firstMessageIndex = session.mSession.firstKnownIndex
        } catch (e: OlmException) {
            Log.e(LOG_TAG, "OlmException", e)
        }

        keyBackupData.forwardedCount = session.mForwardingCurve25519KeyChain.size
        keyBackupData.isVerified = device?.isVerified == true

        val data = mapOf(
                "ciphertext" to encryptedSessionBackupData!!.mCipherText,
                "mac" to encryptedSessionBackupData.mMac,
                "ephemeral" to encryptedSessionBackupData.mEphemeralKey)

        keyBackupData.sessionData = JsonUtils.getGson(false).toJsonTree(data)

        return keyBackupData
    }

    @VisibleForTesting
    @WorkerThread
    fun decryptKeyBackupData(keyBackupData: KeyBackupData, sessionId: String, roomId: String, decryption: OlmPkDecryption): MegolmSessionData? {
        var sessionBackupData: MegolmSessionData? = null

        val jsonObject = keyBackupData.sessionData?.asJsonObject

        val ciphertext = jsonObject?.get("ciphertext")?.asString
        val mac = jsonObject?.get("mac")?.asString
        val ephemeralKey = jsonObject?.get("ephemeral")?.asString

        if (ciphertext != null && mac != null && ephemeralKey != null) {
            val encrypted = OlmPkMessage()
            encrypted.mCipherText = ciphertext
            encrypted.mMac = mac
            encrypted.mEphemeralKey = ephemeralKey

            try {
                val decrypted = decryption.decrypt(encrypted)
                sessionBackupData = JsonUtils.toClass(decrypted, MegolmSessionData::class.java)
            } catch (e: OlmException) {
                Log.e(LOG_TAG, "OlmException", e)
            }

            if (sessionBackupData != null) {
                sessionBackupData.sessionId = sessionId
                sessionBackupData.roomId = roomId
            }
        }

        return sessionBackupData
    }

    companion object {
        private val LOG_TAG = KeysBackup::class.java.simpleName

        // Maximum delay in ms in {@link maybeBackupKeys}
        private const val KEY_BACKUP_WAITING_TIME_TO_SEND_KEY_BACKUP_MILLIS = 10000

        // Maximum number of keys to send at a time to the homeserver.
        private const val KEY_BACKUP_SEND_KEYS_MAX_COUNT = 100
    }

    /* ==========================================================================================
     * DEBUG INFO
     * ========================================================================================== */

    override fun toString() = "KeysBackup for $mCrypto"
}
