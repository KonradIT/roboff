package com.roboff.lan.auth

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONException
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-only encrypted profile storage backed by Android Keystore. */
class RobotStore(context: Context) {
    private val preferences: SharedPreferences = context.applicationContext
        .getSharedPreferences("robot_credentials", Context.MODE_PRIVATE)

    fun load(): List<RobotProfile> = synchronized(LOCK) { readProfiles() }

    @Throws(StoreException::class)
    fun save(profile: RobotProfile) = saveAll(listOf(profile))

    /** Atomically imports devices while retaining unrelated local pairings. */
    @Throws(StoreException::class)
    fun saveAll(imported: List<RobotProfile>) = synchronized(LOCK) {
        writeProfiles(ProfileMerger.merge(readProfiles(), imported))
    }

    @Throws(StoreException::class)
    fun remove(id: String) = synchronized(LOCK) {
        val profiles = readProfiles().filterNot { it.id == id }
        if (profiles.isEmpty()) {
            if (!preferences.edit().remove(RECORD).commit()) {
                throw StoreException("Could not remove the saved vacuum.")
            }
        } else {
            writeProfiles(profiles)
        }
    }

    private fun readProfiles(): MutableList<RobotProfile> {
        val saved = preferences.getString(RECORD, null) ?: return mutableListOf()
        try {
            val encrypted = Base64.decode(saved, Base64.NO_WRAP)
            if (encrypted.size < IV_LENGTH + 16 || encrypted.size > 262_144) {
                throw GeneralSecurityException("Invalid encrypted profile size")
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getKey(allowCreate = false), GCMParameterSpec(128, encrypted, 0, IV_LENGTH))
            cipher.updateAAD(AAD)
            val plaintext = cipher.doFinal(encrypted, IV_LENGTH, encrypted.size - IV_LENGTH)
            val array = try {
                JSONArray(String(plaintext, StandardCharsets.UTF_8))
            } finally {
                plaintext.fill(0)
            }
            return ProfileCodec.decode(array).toMutableList()
        } catch (error: GeneralSecurityException) {
            throw lockedStore(error)
        } catch (error: IOException) {
            throw lockedStore(error)
        } catch (error: JSONException) {
            throw lockedStore(error)
        } catch (error: IllegalArgumentException) {
            throw lockedStore(error)
        }
    }

    private fun writeProfiles(profiles: List<RobotProfile>) {
        try {
            val array = ProfileCodec.encode(profiles)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getKey(allowCreate = true))
            cipher.updateAAD(AAD)
            val plaintext = array.toString().toByteArray(StandardCharsets.UTF_8)
            val ciphertext = try {
                cipher.doFinal(plaintext)
            } finally {
                plaintext.fill(0)
            }
            val iv = cipher.iv
            if (iv.size != IV_LENGTH) throw GeneralSecurityException("Invalid encryption IV length")
            val packed = iv + ciphertext
            if (packed.size > 262_144) throw StoreException("The imported robot catalog is too large to save.")
            if (!preferences.edit().putString(RECORD, Base64.encodeToString(packed, Base64.NO_WRAP)).commit()) {
                throw StoreException("Could not save the local credentials.")
            }
        } catch (error: GeneralSecurityException) {
            throw StoreException("Could not protect the local credentials on this device.", error)
        } catch (error: IOException) {
            throw StoreException("Could not protect the local credentials on this device.", error)
        } catch (error: JSONException) {
            throw StoreException("Could not protect the local credentials on this device.", error)
        }
    }

    private fun getKey(allowCreate: Boolean): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        if (keyStore.containsAlias(KEY_ALIAS)) return keyStore.getKey(KEY_ALIAS, null) as SecretKey
        if (!allowCreate) throw GeneralSecurityException("Encryption key is unavailable")
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun lockedStore(cause: Exception) = StoreException(
        "The saved local credentials could not be unlocked. Remove app data and pair again.",
        cause,
    )

    class StoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

    companion object {
        private val LOCK = Any()
        private const val KEY_ALIAS = "com.roboff.lan.robot-profiles.v1"
        private const val RECORD = "profiles-v1"
        private val AAD = "Roboff profiles v1".toByteArray(StandardCharsets.UTF_8)
        private const val IV_LENGTH = 12
    }
}
