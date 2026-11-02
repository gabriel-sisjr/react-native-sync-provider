package com.margelo.nitro.syncprovider.config

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM encryption under a non-exportable Android Keystore key. Only the
 * ciphertext and its IV reach SharedPreferences.
 *
 * The key never leaves the device, so ciphertext restored from a backup onto another
 * device reads as [SecretRead.Unavailable]. That is the intended failure: the caller
 * must not dispatch without credentials it was told to use.
 *
 * Exception messages are not logged, only their type: nothing here may risk putting
 * key or header material in a log.
 */
internal class KeystoreSecretStore(context: Context) : SecretStore {

  private val prefs: SharedPreferences =
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  override fun read(): SecretRead {
    val ciphertext = prefs.getString(KEY_CIPHERTEXT, null)
    val iv = prefs.getString(KEY_IV, null)
    if (ciphertext == null || iv == null) return SecretRead.Absent
    return try {
      val key = existingKey() ?: return SecretRead.Unavailable
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, decode(iv)))
      SecretRead.Present(String(cipher.doFinal(decode(ciphertext)), Charsets.UTF_8))
    } catch (e: Exception) {
      Log.w(TAG, "Stored secret could not be decrypted: ${e.javaClass.simpleName}")
      SecretRead.Unavailable
    }
  }

  override fun write(value: String): Boolean = try {
    val cipher = runCatching { encryptingCipher() }.getOrElse {
      // A key the Keystore can no longer use would otherwise fail every future write.
      keyStore().deleteEntry(KEY_ALIAS)
      encryptingCipher()
    }
    val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
    prefs.edit()
      .putString(KEY_CIPHERTEXT, encode(ciphertext))
      .putString(KEY_IV, encode(cipher.iv))
      .commit()
  } catch (e: Exception) {
    Log.w(TAG, "Secret could not be stored: ${e.javaClass.simpleName}")
    false
  }

  override fun delete() {
    prefs.edit().remove(KEY_CIPHERTEXT).remove(KEY_IV).commit()
  }

  private fun encryptingCipher(): Cipher =
    Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, existingKey() ?: generateKey()) }

  private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

  private fun existingKey(): SecretKey? = keyStore().getKey(KEY_ALIAS, null) as? SecretKey

  private fun generateKey(): SecretKey {
    val spec = KeyGenParameterSpec.Builder(
      KEY_ALIAS,
      KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
    )
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
      .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
      .setKeySize(AES_KEY_BITS)
      .build()
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
      .apply { init(spec) }
      .generateKey()
  }

  private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

  private fun decode(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)

  private companion object {
    const val TAG = "KeystoreSecretStore"
    const val PREFS_NAME = "syncprovider.secret.prefs"
    const val KEY_CIPHERTEXT = "ciphertext"
    const val KEY_IV = "iv"
    const val ANDROID_KEYSTORE = "AndroidKeyStore"
    const val KEY_ALIAS = "syncprovider.default-headers"
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val AES_KEY_BITS = 256
    const val GCM_TAG_BITS = 128
  }
}
