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

import android.support.test.InstrumentationRegistry
import android.support.test.runner.AndroidJUnit4
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.matrix.androidsdk.MXSession
import org.matrix.androidsdk.common.*
import org.matrix.androidsdk.crypto.MXCRYPTO_ALGORITHM_MEGOLM_BACKUP
import org.matrix.androidsdk.crypto.MegolmSessionData
import org.matrix.androidsdk.crypto.data.ImportRoomKeysResult
import org.matrix.androidsdk.crypto.data.MXDeviceInfo
import org.matrix.androidsdk.crypto.data.MXOlmInboundGroupSession2
import org.matrix.androidsdk.rest.callback.SuccessCallback
import org.matrix.androidsdk.rest.callback.SuccessErrorCallback
import org.matrix.androidsdk.rest.model.keys.CreateKeysBackupVersionBody
import org.matrix.androidsdk.rest.model.keys.KeysVersion
import org.matrix.androidsdk.rest.model.keys.KeysVersionResult
import org.matrix.androidsdk.util.JsonUtils
import java.util.concurrent.CountDownLatch

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class KeysBackupTest {

    private val mTestHelper = CommonTestHelper()
    private val mCryptoTestHelper = CryptoTestHelper(mTestHelper)

    private val defaultSessionParams = SessionTestParams(
            withInitialSync = false,
            withCryptoEnabled = true,
            withLazyLoading = true,
            withLegacyCryptoStore = false)
    private val defaultSessionParamsWithInitialSync = SessionTestParams(
            withInitialSync = true,
            withCryptoEnabled = true,
            withLazyLoading = true,
            withLegacyCryptoStore = false)

    /**
     * - From doE2ETestWithAliceAndBobInARoomWithEncryptedMessages, we should have no backed up keys
     * - Check backup keys after having marked one as backed up
     * - Reset keys backup markers
     */
    @Test
    fun roomKeysTest_testBackupStore_ok() {
        val cryptoTestData = mCryptoTestHelper.doE2ETestWithAliceAndBobInARoomWithEncryptedMessages(true)

        val store = cryptoTestData.firstSession.crypto!!.cryptoStore

        // From doE2ETestWithAliceAndBobInARoomWithEncryptedMessages, we should have no backed up keys
        val sessions = store.inboundGroupSessionsToBackup(100)
        val sessionsCount = sessions.size

        assertFalse(sessions.isEmpty())
        assertEquals(sessionsCount, store.inboundGroupSessionsCount(false))
        assertEquals(0, store.inboundGroupSessionsCount(true))

        // - Check backup keys after having marked one as backed up
        val session = sessions[0]

        store.markBackupDoneForInboundGroupSessionWithId(session.mSession.sessionIdentifier(), session.mSenderKey)

        assertEquals(sessionsCount, store.inboundGroupSessionsCount(false))
        assertEquals(1, store.inboundGroupSessionsCount(true))

        val sessions2 = store.inboundGroupSessionsToBackup(100)
        assertEquals(sessionsCount - 1, sessions2.size)

        // - Reset keys backup markers
        store.resetBackupMarkers()

        val sessions3 = store.inboundGroupSessionsToBackup(100)
        assertEquals(sessionsCount, sessions3.size)
        assertEquals(sessionsCount, store.inboundGroupSessionsCount(false))
        assertEquals(0, store.inboundGroupSessionsCount(true))
    }

    /**
     * Check that prepareKeysBackupVersionWithPassword returns valid data
     */
    @Test
    fun prepareKeysBackupVersionTest() {
        val bobSession = mTestHelper.createAccount(TestConstants.USER_BOB, defaultSessionParams)

        bobSession.enableCrypto(mTestHelper)

        assertNotNull(bobSession.crypto)
        assertNotNull(bobSession.crypto!!.keysBackup)

        val keysBackup = bobSession.crypto!!.keysBackup

        assertFalse(keysBackup.isEnabled)

        val latch = CountDownLatch(1)

        keysBackup.prepareKeysBackupVersion(null, object : SuccessErrorCallback<MegolmBackupCreationInfo> {
            override fun onSuccess(info: MegolmBackupCreationInfo?) {
                assertNotNull(info)

                assertEquals(MXCRYPTO_ALGORITHM_MEGOLM_BACKUP, info!!.algorithm)
                assertNotNull(info.authData)
                assertNotNull(info.authData!!.publicKey)
                assertNotNull(info.authData!!.signatures)
                assertNotNull(info.recoveryKey)

                latch.countDown()
            }

            override fun onUnexpectedError(e: Exception?) {
                fail(e?.localizedMessage)

                latch.countDown()
            }
        })
        latch.await()

        bobSession.clear(InstrumentationRegistry.getContext())
    }

    /**
     * Test creating a keys backup version and check that createKeysBackupVersion() returns valid data
     */
    @Test
    fun createKeysBackupVersionTest() {
        val bobSession = mTestHelper.createAccount(TestConstants.USER_BOB, defaultSessionParams)
        bobSession.enableCrypto(mTestHelper)

        val keysBackup = bobSession.crypto!!.keysBackup

        assertFalse(keysBackup.isEnabled)

        var megolmBackupCreationInfo: MegolmBackupCreationInfo? = null
        val latch = CountDownLatch(1)
        keysBackup.prepareKeysBackupVersion(null, object : SuccessErrorCallback<MegolmBackupCreationInfo> {

            override fun onSuccess(info: MegolmBackupCreationInfo) {
                megolmBackupCreationInfo = info

                latch.countDown()
            }

            override fun onUnexpectedError(e: Exception) {
                fail(e.localizedMessage)

                latch.countDown()
            }
        })
        latch.await()

        assertNotNull(megolmBackupCreationInfo)

        assertFalse(keysBackup.isEnabled)

        val latch2 = CountDownLatch(1)

        // Create the version
        keysBackup.createKeysBackupVersion(megolmBackupCreationInfo!!, object : TestApiCallback<KeysVersion>(latch2) {
            override fun onSuccess(info: KeysVersion) {
                assertNotNull(info)
                assertNotNull(info.version)

                super.onSuccess(info)
            }
        })
        mTestHelper.await(latch2)

        // Backup must be enable now
        assertTrue(keysBackup.isEnabled)

        bobSession.clear(InstrumentationRegistry.getContext())
    }

    /**
     * - Check that createKeysBackupVersion() launches the backup
     * - Check the backup completes
     */
    @Test
    fun backupAfterCreateKeysBackupVersionTest() {
        val context = InstrumentationRegistry.getContext()
        val cryptoTestData = mCryptoTestHelper.doE2ETestWithAliceAndBobInARoomWithEncryptedMessages(true)

        val cryptoStore = cryptoTestData.firstSession.crypto!!.cryptoStore
        val keysBackup = cryptoTestData.firstSession.crypto!!.keysBackup

        val latch = CountDownLatch(1)
        var counter = 0

        assertEquals(2, cryptoStore.inboundGroupSessionsCount(false))
        assertEquals(0, cryptoStore.inboundGroupSessionsCount(true))

        val stateList = ArrayList<KeysBackupStateManager.KeysBackupState>()

        keysBackup.addListener(object : KeysBackupStateManager.KeysBackupStateListener {
            override fun onStateChange(newState: KeysBackupStateManager.KeysBackupState) {
                stateList.add(newState)

                counter++

                if (counter == 5) {
                    // Last state
                    // Remove itself from the list of listeners
                    keysBackup.removeListener(this)

                    latch.countDown()
                }
            }
        })

        prepareAndCreateKeysBackupData(keysBackup)

        mTestHelper.await(latch)

        // Check the several backup state changes
        assertEquals(KeysBackupStateManager.KeysBackupState.Enabling, stateList[0])
        assertEquals(KeysBackupStateManager.KeysBackupState.ReadyToBackUp, stateList[1])
        assertEquals(KeysBackupStateManager.KeysBackupState.WillBackUp, stateList[2])
        assertEquals(KeysBackupStateManager.KeysBackupState.BackingUp, stateList[3])
        assertEquals(KeysBackupStateManager.KeysBackupState.ReadyToBackUp, stateList[4])

        val nbOfKeys = cryptoStore.inboundGroupSessionsCount(false)
        val backedUpKeys = cryptoStore.inboundGroupSessionsCount(true)

        assertEquals(2, nbOfKeys)
        assertEquals("All keys must have been marked as backed up", nbOfKeys, backedUpKeys)

        cryptoTestData.clear(context)
    }


    /**
     * Check that backupAllGroupSessions() returns valid data
     */
    @Test
    fun backupAllGroupSessionsTest() {
        val context = InstrumentationRegistry.getContext()
        val cryptoTestData = mCryptoTestHelper.doE2ETestWithAliceAndBobInARoomWithEncryptedMessages(true)

        val cryptoStore = cryptoTestData.firstSession.crypto!!.cryptoStore
        val keysBackup = cryptoTestData.firstSession.crypto!!.keysBackup

        prepareAndCreateKeysBackupData(keysBackup)

        // Check that backupAllGroupSessions returns valid data
        val nbOfKeys = cryptoStore.inboundGroupSessionsCount(false)

        assertEquals(2, nbOfKeys)

        val latch = CountDownLatch(1)

        var lastBackedUpKeysProgress = 0

        keysBackup.backupAllGroupSessions(object : KeysBackup.BackupProgressListener {
            override fun onProgress(backedUp: Int, total: Int) {
                assertEquals(nbOfKeys, total)
                lastBackedUpKeysProgress = backedUp
            }

        }, TestApiCallback(latch))

        mTestHelper.await(latch)
        assertEquals(nbOfKeys, lastBackedUpKeysProgress)

        val backedUpKeys = cryptoStore.inboundGroupSessionsCount(true)

        assertEquals("All keys must have been marked as backed up", nbOfKeys, backedUpKeys)

        cryptoTestData.clear(context)
    }

    /**
     * Check encryption and decryption of megolm keys in the backup.
     * - Pick a megolm key
     * - Check [MXKeyBackup encryptGroupSession] returns stg
     * - Check [MXKeyBackup pkDecryptionFromRecoveryKey] is able to create a OLMPkDecryption
     * - Check [MXKeyBackup decryptKeyBackupData] returns stg
     * - Compare the decrypted megolm key with the original one
     */
    @Test
    fun testEncryptAndDecryptKeysBackupData() {
        val context = InstrumentationRegistry.getContext()
        val cryptoTestData = mCryptoTestHelper.doE2ETestWithAliceAndBobInARoomWithEncryptedMessages(true)

        val cryptoStore = cryptoTestData.firstSession.crypto!!.cryptoStore
        val keysBackup = cryptoTestData.firstSession.crypto!!.keysBackup

        // - Pick a megolm key
        val session = cryptoStore.inboundGroupSessionsToBackup(1)[0]

        val keyBackupCreationInfo = prepareAndCreateKeysBackupData(keysBackup).megolmBackupCreationInfo

        // - Check encryptGroupSession() returns stg
        val keyBackupData = keysBackup.encryptGroupSession(session)
        assertNotNull(keyBackupData)
        assertNotNull(keyBackupData.sessionData)

        // - Check pkDecryptionFromRecoveryKey() is able to create a OlmPkDecryption
        val decryption = keysBackup.pkDecryptionFromRecoveryKey(keyBackupCreationInfo.recoveryKey)
        assertNotNull(decryption)
        // - Check decryptKeyBackupData() returns stg
        val sessionData = keysBackup.decryptKeyBackupData(keyBackupData, session.mSession.sessionIdentifier(), cryptoTestData.roomId, decryption!!)
        assertNotNull(sessionData)
        // - Compare the decrypted megolm key with the original one
        assertKeysEquals(session.exportKeys(), sessionData)

        cryptoTestData.clear(context)
    }

    /**
     * - Do an e2e backup to the homeserver with a recovery key
     * - Log Alice on a new device
     * - Restore the e2e backup from the homeserver with the recovery key
     * - Restore must be successful
     */
    @Test
    fun restoreKeysBackupTest() {
        val context = InstrumentationRegistry.getContext()

        val testData = createKeysBackupScenarioWithPassword(null)

        // - Restore the e2e backup from the homeserver
        val latch2 = CountDownLatch(1)
        var importRoomKeysResult: ImportRoomKeysResult? = null
        testData.aliceSession2.crypto!!.keysBackup.restoreKeysWithRecoveryKey(testData.prepareKeysBackupDataResult.version,
                testData.prepareKeysBackupDataResult.megolmBackupCreationInfo.recoveryKey,
                null,
                null,
                object : TestApiCallback<ImportRoomKeysResult>(latch2) {
                    override fun onSuccess(info: ImportRoomKeysResult) {
                        importRoomKeysResult = info
                        super.onSuccess(info)
                    }
                }
        )
        mTestHelper.await(latch2)

        checkRestoreSuccess(testData, importRoomKeysResult!!.totalNumberOfKeys, importRoomKeysResult!!.successfullyNumberOfImportedKeys)

        testData.cryptoTestData.clear(context)
    }

    /**
     * - Do an e2e backup to the homeserver with a recovery key
     * - Log Alice on a new device
     * - Try to restore the e2e backup with a wrong recovery key
     * - It must fail
     */
    @Test
    fun restoreKeysBackupWithAWrongRecoveryKeyTest() {
        val context = InstrumentationRegistry.getContext()

        val testData = createKeysBackupScenarioWithPassword(null)

        // - Try to restore the e2e backup with a wrong recovery key
        val latch2 = CountDownLatch(1)
        var importRoomKeysResult: ImportRoomKeysResult? = null
        testData.aliceSession2.crypto!!.keysBackup.restoreKeysWithRecoveryKey(testData.prepareKeysBackupDataResult.version,
                "EsTc LW2K PGiF wKEA 3As5 g5c4 BXwk qeeJ ZJV8 Q9fu gUMN UE4d",
                null,
                null,
                object : TestApiCallback<ImportRoomKeysResult>(latch2, false) {
                    override fun onSuccess(info: ImportRoomKeysResult) {
                        importRoomKeysResult = info
                        super.onSuccess(info)
                    }
                }
        )
        mTestHelper.await(latch2)

        // onSuccess may not have been called
        assertNull(importRoomKeysResult)

        testData.cryptoTestData.clear(context)
    }

    /**
     * - Do an e2e backup to the homeserver with a password
     * - Log Alice on a new device
     * - Restore the e2e backup with the password
     * - Restore must be successful
     */
    @Test
    fun testBackupWithPassword() {
        val password = "password"

        val context = InstrumentationRegistry.getContext()

        val testData = createKeysBackupScenarioWithPassword(password)

        // - Restore the e2e backup with the password
        val latch2 = CountDownLatch(1)
        var importRoomKeysResult: ImportRoomKeysResult? = null
        testData.aliceSession2.crypto!!.keysBackup.restoreKeyBackupWithPassword(testData.prepareKeysBackupDataResult.version,
                password,
                null,
                null,
                object : TestApiCallback<ImportRoomKeysResult>(latch2) {
                    override fun onSuccess(info: ImportRoomKeysResult) {
                        importRoomKeysResult = info
                        super.onSuccess(info)
                    }
                }
        )
        mTestHelper.await(latch2)

        checkRestoreSuccess(testData, importRoomKeysResult!!.totalNumberOfKeys, importRoomKeysResult!!.successfullyNumberOfImportedKeys)

        testData.cryptoTestData.clear(context)
    }

    /**
     * - Do an e2e backup to the homeserver with a password
     * - Log Alice on a new device
     * - Try to restore the e2e backup with a wrong password
     * - It must fail
     */
    @Test
    fun restoreKeysBackupWithAWrongPasswordTest() {
        val password = "password"
        val wrongPassword = "passw0rd"

        val context = InstrumentationRegistry.getContext()

        val testData = createKeysBackupScenarioWithPassword(password)

        // - Try to restore the e2e backup with a wrong password
        val latch2 = CountDownLatch(1)
        var importRoomKeysResult: ImportRoomKeysResult? = null
        testData.aliceSession2.crypto!!.keysBackup.restoreKeyBackupWithPassword(testData.prepareKeysBackupDataResult.version,
                wrongPassword,
                null,
                null,
                object : TestApiCallback<ImportRoomKeysResult>(latch2, false) {
                    override fun onSuccess(info: ImportRoomKeysResult) {
                        importRoomKeysResult = info
                        super.onSuccess(info)
                    }
                }
        )
        mTestHelper.await(latch2)

        // onSuccess may not have been called
        assertNull(importRoomKeysResult)

        testData.cryptoTestData.clear(context)
    }

    /**
     * - Do an e2e backup to the homeserver with a password
     * - Log Alice on a new device
     * - Restore the e2e backup with the recovery key.
     * - Restore must be successful
     */
    @Test
    fun testUseRecoveryKeyToRestoreAPasswordBasedKeysBackup() {
        val password = "password"

        val context = InstrumentationRegistry.getContext()

        val testData = createKeysBackupScenarioWithPassword(password)

        // - Restore the e2e backup with the recovery key.
        val latch2 = CountDownLatch(1)
        var importRoomKeysResult: ImportRoomKeysResult? = null
        testData.aliceSession2.crypto!!.keysBackup.restoreKeysWithRecoveryKey(testData.prepareKeysBackupDataResult.version,
                testData.prepareKeysBackupDataResult.megolmBackupCreationInfo.recoveryKey,
                null,
                null,
                object : TestApiCallback<ImportRoomKeysResult>(latch2) {
                    override fun onSuccess(info: ImportRoomKeysResult) {
                        importRoomKeysResult = info
                        super.onSuccess(info)
                    }
                }
        )
        mTestHelper.await(latch2)

        checkRestoreSuccess(testData, importRoomKeysResult!!.totalNumberOfKeys, importRoomKeysResult!!.successfullyNumberOfImportedKeys)

        testData.cryptoTestData.clear(context)
    }

    /**
     * - Do an e2e backup to the homeserver with a recovery key
     * - And log Alice on a new device
     * - Try to restore the e2e backup with a password
     * - It must fail
     */
    @Test
    fun testUsePasswordToRestoreARecoveryKeyBasedKeysBackup() {
        val context = InstrumentationRegistry.getContext()

        val testData = createKeysBackupScenarioWithPassword(null)

        // - Try to restore the e2e backup with a password
        val latch2 = CountDownLatch(1)
        var importRoomKeysResult: ImportRoomKeysResult? = null
        testData.aliceSession2.crypto!!.keysBackup.restoreKeyBackupWithPassword(testData.prepareKeysBackupDataResult.version,
                "password",
                null,
                null,
                object : TestApiCallback<ImportRoomKeysResult>(latch2, false) {
                    override fun onSuccess(info: ImportRoomKeysResult) {
                        importRoomKeysResult = info
                        super.onSuccess(info)
                    }
                }
        )
        mTestHelper.await(latch2)

        // onSuccess may not have been called
        assertNull(importRoomKeysResult)

        testData.cryptoTestData.clear(context)
    }

    /**
     * - Create a backup version
     * - Check the returned KeysVersionResult is trusted
     */
    @Test
    fun testIsKeysBackupTrusted() {
        // - Create a backup version
        val context = InstrumentationRegistry.getContext()
        val cryptoTestData = mCryptoTestHelper.doE2ETestWithAliceAndBobInARoomWithEncryptedMessages(true)

        val keysBackup = cryptoTestData.firstSession.crypto!!.keysBackup

        // - Do an e2e backup to the homeserver
        prepareAndCreateKeysBackupData(keysBackup)

        // Get key backup version from the home server
        var keysVersionResult: KeysVersionResult? = null
        val lock = CountDownLatch(1)
        keysBackup.getCurrentVersion(object : TestApiCallback<KeysVersionResult?>(lock) {
            override fun onSuccess(info: KeysVersionResult?) {
                keysVersionResult = info
                super.onSuccess(info)
            }
        })
        mTestHelper.await(lock)

        assertNotNull(keysVersionResult)

        // - Check the returned KeyBackupVersion is trusted
        val latch = CountDownLatch(1)
        var keysBackupVersionTrust: KeysBackupVersionTrust? = null
        keysBackup.getKeysBackupTrust(keysVersionResult!!, SuccessCallback { info ->
            keysBackupVersionTrust = info

            latch.countDown()
        })
        mTestHelper.await(latch)

        assertNotNull(keysBackupVersionTrust)
        assertTrue(keysBackupVersionTrust!!.usable)
        assertEquals(1, keysBackupVersionTrust!!.signatures.size)

        val signature = keysBackupVersionTrust!!.signatures[0]
        assertTrue(signature.valid)
        assertNotNull(signature.device)
        assertEquals(cryptoTestData.firstSession.crypto?.myDevice?.deviceId, signature.deviceId)
        assertEquals(signature.device!!.deviceId, cryptoTestData.firstSession.credentials.deviceId)

        cryptoTestData.clear(context)
    }

    /**
     * Check backup starts automatically if there is an existing and compatible backup
     * version on the homeserver.
     * - Create a backup version
     * - Restart alice session
     * -> The new alice session must back up to the same version
     */
    @Test
    fun testCheckAndStartKeysBackupWhenRestartingAMatrixSession() {
        // - Create a backup version
        val context = InstrumentationRegistry.getContext()
        val cryptoTestData = mCryptoTestHelper.doE2ETestWithAliceAndBobInARoomWithEncryptedMessages(true)

        val keysBackup = cryptoTestData.firstSession.crypto!!.keysBackup

        assertFalse(keysBackup.isEnabled)

        val keyBackupCreationInfo = prepareAndCreateKeysBackupData(keysBackup)

        assertTrue(keysBackup.isEnabled)

        // - Restart alice session
        // - Log Alice on a new device
        val aliceSession2 = mTestHelper.logIntoAccount(cryptoTestData.firstSession.myUserId, defaultSessionParamsWithInitialSync)

        cryptoTestData.clear(context)

        val keysBackup2 = aliceSession2.crypto!!.keysBackup

        // -> The new alice session must back up to the same version
        val latch = CountDownLatch(1)
        var count = 0
        keysBackup2.addListener(object : KeysBackupStateManager.KeysBackupStateListener {
            override fun onStateChange(newState: KeysBackupStateManager.KeysBackupState) {
                // Check the backup completes
                if (keysBackup.state == KeysBackupStateManager.KeysBackupState.ReadyToBackUp) {
                    count++

                    if (count == 2) {
                        // Remove itself from the list of listeners
                        keysBackup.removeListener(this)

                        latch.countDown()
                    }
                }
            }
        })
        mTestHelper.await(latch)

        assertEquals(keyBackupCreationInfo.version, keysBackup2.currentBackupVersion)

        aliceSession2.clear(context)
    }

    /**
     * Check WrongBackUpVersion state
     *
     * - Make alice back up her keys to her homeserver
     * - Create a new backup with fake data on the homeserver
     * - Make alice back up all her keys again
     * -> That must fail and her backup state must be WrongBackUpVersion
     */
    @Test
    fun testBackupWhenAnotherBackupWasCreated() {
        // - Create a backup version
        val context = InstrumentationRegistry.getContext()
        val cryptoTestData = mCryptoTestHelper.doE2ETestWithAliceAndBobInARoomWithEncryptedMessages(true)

        val keysBackup = cryptoTestData.firstSession.crypto!!.keysBackup

        assertFalse(keysBackup.isEnabled)

        // Wait for keys backup to be finished
        val latch0 = CountDownLatch(1)
        var count = 0
        keysBackup.addListener(object : KeysBackupStateManager.KeysBackupStateListener {
            override fun onStateChange(newState: KeysBackupStateManager.KeysBackupState) {
                // Check the backup completes
                if (newState == KeysBackupStateManager.KeysBackupState.ReadyToBackUp) {
                    count++

                    if (count == 2) {
                        // Remove itself from the list of listeners
                        keysBackup.removeListener(this)

                        latch0.countDown()
                    }
                }
            }
        })

        // - Make alice back up her keys to her homeserver
        prepareAndCreateKeysBackupData(keysBackup)

        assertTrue(keysBackup.isEnabled)

        mTestHelper.await(latch0)

        // - Create a new backup with fake data on the homeserver, directly using the rest client
        val latch = CountDownLatch(1)

        val megolmBackupCreationInfo = mCryptoTestHelper.createFakeMegolmBackupCreationInfo()
        val createKeysBackupVersionBody = CreateKeysBackupVersionBody()
        createKeysBackupVersionBody.algorithm = megolmBackupCreationInfo.algorithm
        createKeysBackupVersionBody.authData = JsonUtils.getBasicGson().toJsonTree(megolmBackupCreationInfo.authData)
        cryptoTestData.firstSession.roomKeysRestClient.createKeysBackupVersion(createKeysBackupVersionBody, TestApiCallback(latch))
        mTestHelper.await(latch)

        // Reset the store backup status for keys
        cryptoTestData.firstSession.crypto!!.cryptoStore.resetBackupMarkers()

        // - Make alice back up all her keys again
        val latch2 = CountDownLatch(1)
        keysBackup.backupAllGroupSessions(object : KeysBackup.BackupProgressListener {
            override fun onProgress(backedUp: Int, total: Int) {
            }

        }, TestApiCallback(latch2, false))
        mTestHelper.await(latch2)

        // -> That must fail and her backup state must be WrongBackUpVersion
        assertEquals(KeysBackupStateManager.KeysBackupState.WrongBackUpVersion, keysBackup.state)
        assertFalse(keysBackup.isEnabled)

        cryptoTestData.clear(context)
    }

    /**
     * - Do an e2e backup to the homeserver
     * - Log Alice on a new device
     * - Post a message to have a new megolm session
     * - Try to backup all
     * -> It must fail. Backup state must be NotTrusted
     * - Validate the old device from the new one
     * -> Backup should automatically enable on the new device
     * -> It must use the same backup version
     * - Try to backup all again
     * -> It must success
     */
    @Test
    fun testBackupAfterVerifyingADevice() {
        // - Create a backup version
        val context = InstrumentationRegistry.getContext()
        val cryptoTestData = mCryptoTestHelper.doE2ETestWithAliceAndBobInARoomWithEncryptedMessages(true)

        val keysBackup = cryptoTestData.firstSession.crypto!!.keysBackup

        // - Make alice back up her keys to her homeserver
        prepareAndCreateKeysBackupData(keysBackup)

        // Wait for keys backup to finish by asking again to backup keys.
        val latch = CountDownLatch(1)
        keysBackup.backupAllGroupSessions(object : KeysBackup.BackupProgressListener {
            override fun onProgress(backedUp: Int, total: Int) {

            }
        }, TestApiCallback(latch))
        mTestHelper.await(latch)

        val oldDeviceId = cryptoTestData.firstSession.credentials.deviceId
        val oldKeyBackupVersion = keysBackup.currentBackupVersion
        val aliceUserId = cryptoTestData.firstSession.myUserId

        // Close first Alice session, else they will share the same Crypto store and the test fails.
        cryptoTestData.firstSession.clear(context)

        // - Log Alice on a new device
        val aliceSession2 = mTestHelper.logIntoAccount(aliceUserId, defaultSessionParamsWithInitialSync)

        // - Post a message to have a new megolm session
        aliceSession2.crypto!!.setWarnOnUnknownDevices(false)

        val room2 = aliceSession2.dataHandler.getRoom(cryptoTestData.roomId)

        mTestHelper.sendTextMessage(room2, "New key", 1)

        // - Try to backup all in aliceSession2, it must fail
        val keysBackup2 = aliceSession2.crypto!!.keysBackup

        var isSuccessful = false
        val latch2 = CountDownLatch(1)
        keysBackup2.backupAllGroupSessions(object : KeysBackup.BackupProgressListener {
            override fun onProgress(backedUp: Int, total: Int) {
            }

        }, object : TestApiCallback<Void?>(latch2, false) {
            override fun onSuccess(info: Void?) {
                isSuccessful = true
                super.onSuccess(info)
            }
        })
        mTestHelper.await(latch2)

        assertFalse(isSuccessful)

        // Backup state must be NotTrusted
        assertEquals(KeysBackupStateManager.KeysBackupState.NotTrusted, keysBackup2.state)
        assertFalse(keysBackup2.isEnabled)

        // - Validate the old device from the new one
        val latch3 = CountDownLatch(1)
        aliceSession2.crypto!!.setDeviceVerification(MXDeviceInfo.DEVICE_VERIFICATION_VERIFIED, oldDeviceId, aliceSession2.myUserId, TestApiCallback(latch3))
        mTestHelper.await(latch3)

        // -> Backup should automatically enable on the new device
        val latch4 = CountDownLatch(1)
        keysBackup2.addListener(object : KeysBackupStateManager.KeysBackupStateListener {
            override fun onStateChange(newState: KeysBackupStateManager.KeysBackupState) {
                // Check the backup completes
                if (keysBackup2.state == KeysBackupStateManager.KeysBackupState.ReadyToBackUp) {
                    // Remove itself from the list of listeners
                    keysBackup2.removeListener(this)

                    latch4.countDown()
                }
            }
        })
        mTestHelper.await(latch4)

        // -> It must use the same backup version
        assertEquals(oldKeyBackupVersion, aliceSession2.crypto!!.keysBackup.currentBackupVersion)

        val latch5 = CountDownLatch(1)
        aliceSession2.crypto!!.keysBackup.backupAllGroupSessions(null, TestApiCallback(latch5))
        mTestHelper.await(latch5)

        // -> It must success
        assertTrue(aliceSession2.crypto!!.keysBackup.isEnabled)

        aliceSession2.clear(context)
        cryptoTestData.clear(context)
    }

    /* ==========================================================================================
     * Private
     * ========================================================================================== */

    private data class PrepareKeysBackupDataResult(val megolmBackupCreationInfo: MegolmBackupCreationInfo,
                                                   val version: String)

    private fun prepareAndCreateKeysBackupData(keysBackup: KeysBackup,
                                               password: String? = null): PrepareKeysBackupDataResult {
        var megolmBackupCreationInfo: MegolmBackupCreationInfo? = null
        val latch = CountDownLatch(1)
        keysBackup.prepareKeysBackupVersion(password, object : SuccessErrorCallback<MegolmBackupCreationInfo> {

            override fun onSuccess(info: MegolmBackupCreationInfo) {
                megolmBackupCreationInfo = info

                latch.countDown()
            }

            override fun onUnexpectedError(e: Exception) {
                fail(e.localizedMessage)

                latch.countDown()
            }
        })
        mTestHelper.await(latch)

        assertNotNull(megolmBackupCreationInfo)

        assertFalse(keysBackup.isEnabled)

        val latch2 = CountDownLatch(1)

        // Create the version
        var version: String? = null
        keysBackup.createKeysBackupVersion(megolmBackupCreationInfo!!, object : TestApiCallback<KeysVersion>(latch2) {
            override fun onSuccess(info: KeysVersion) {
                assertNotNull(info)
                assertNotNull(info.version)

                version = info.version

                // Backup must be enable now
                assertTrue(keysBackup.isEnabled)

                super.onSuccess(info)
            }
        })
        mTestHelper.await(latch2)

        return PrepareKeysBackupDataResult(megolmBackupCreationInfo!!, version!!)
    }

    private fun assertKeysEquals(keys1: MegolmSessionData?, keys2: MegolmSessionData?) {
        assertNotNull(keys1)
        assertNotNull(keys2)

        assertEquals(keys1?.algorithm, keys2?.algorithm)
        assertEquals(keys1?.roomId, keys2?.roomId)
        // No need to compare the shortcut
        // assertEquals(keys1?.sender_claimed_ed25519_key, keys2?.sender_claimed_ed25519_key)
        assertEquals(keys1?.senderKey, keys2?.senderKey)
        assertEquals(keys1?.sessionId, keys2?.sessionId)
        assertEquals(keys1?.sessionKey, keys2?.sessionKey)

        assertListEquals(keys1?.forwardingCurve25519KeyChain, keys2?.forwardingCurve25519KeyChain)
        assertDictEquals(keys1?.senderClaimedKeys, keys2?.senderClaimedKeys)
    }

    /**
     * Data class to store result of [createKeysBackupScenarioWithPassword]
     */
    private data class KeysBackupScenarioData(val cryptoTestData: CryptoTestData,
                                              val aliceKeys: MutableList<MXOlmInboundGroupSession2>,
                                              val prepareKeysBackupDataResult: PrepareKeysBackupDataResult,
                                              val aliceSession2: MXSession)

    /**
     * Common initial condition
     * - Do an e2e backup to the homeserver
     * - Log Alice on a new device
     *
     * @param password optional password
     */
    private fun createKeysBackupScenarioWithPassword(password: String?): KeysBackupScenarioData {
        val cryptoTestData = mCryptoTestHelper.doE2ETestWithAliceAndBobInARoomWithEncryptedMessages(true)

        val cryptoStore = cryptoTestData.firstSession.crypto!!.cryptoStore
        val keysBackup = cryptoTestData.firstSession.crypto!!.keysBackup

        val aliceKeys = cryptoStore.inboundGroupSessionsToBackup(100)

        // - Do an e2e backup to the homeserver
        val prepareKeysBackupDataResult = prepareAndCreateKeysBackupData(keysBackup, password)

        val latch = CountDownLatch(1)
        var lastBackup = 0
        var lastTotal = 0
        keysBackup.backupAllGroupSessions(object : KeysBackup.BackupProgressListener {
            override fun onProgress(backedUp: Int, total: Int) {
                lastBackup = backedUp
                lastTotal = total
            }
        }, TestApiCallback(latch))
        mTestHelper.await(latch)

        assertEquals(2, lastBackup)
        assertEquals(2, lastTotal)

        // - Log Alice on a new device
        val aliceSession2 = mTestHelper.logIntoAccount(cryptoTestData.firstSession.myUserId, defaultSessionParamsWithInitialSync)

        // Test check: aliceSession2 has no keys at login
        assertEquals(0, aliceSession2.crypto!!.cryptoStore.inboundGroupSessionsCount(false))

        return KeysBackupScenarioData(cryptoTestData,
                aliceKeys,
                prepareKeysBackupDataResult,
                aliceSession2)
    }

    /**
     * Common restore success check after [createKeysBackupScenarioWithPassword]:
     * - Imported keys number must be correct
     * - The new device must have the same count of megolm keys
     * - Alice must have the same keys on both devices
     */
    private fun checkRestoreSuccess(testData: KeysBackupScenarioData,
                                    total: Int,
                                    imported: Int) {
        // - Imported keys number must be correct
        assertEquals(testData.aliceKeys.size, total)
        assertEquals(total, imported)

        // - The new device must have the same count of megolm keys
        assertEquals(testData.aliceKeys.size, testData.aliceSession2.crypto!!.cryptoStore.inboundGroupSessionsCount(false))

        // - Alice must have the same keys on both devices
        for (aliceKey1 in testData.aliceKeys) {
            val aliceKey2 = testData.aliceSession2.crypto!!
                    .cryptoStore.getInboundGroupSession(aliceKey1.mSession.sessionIdentifier(), aliceKey1.mSenderKey)
            assertNotNull(aliceKey2)
            assertKeysEquals(aliceKey1.exportKeys(), aliceKey2!!.exportKeys())
        }
    }
}