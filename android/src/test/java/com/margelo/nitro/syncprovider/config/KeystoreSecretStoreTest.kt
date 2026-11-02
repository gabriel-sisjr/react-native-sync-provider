package com.margelo.nitro.syncprovider.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric has no AndroidKeyStore provider, which makes this the failure path of
 * every real one: a Keystore that does not answer must surface as a return value, not
 * as a crash in a worker. The success path needs a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
internal class KeystoreSecretStoreTest {

  private lateinit var context: Context
  private lateinit var store: KeystoreSecretStore

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    store = KeystoreSecretStore(context)
  }

  private fun seedCiphertext() {
    context.getSharedPreferences("syncprovider.secret.prefs", Context.MODE_PRIVATE).edit()
      .putString("ciphertext", "AAAA")
      .putString("iv", "AAAA")
      .commit()
  }

  @Test
  fun `nothing stored reads as absent, without touching the Keystore`() {
    assertThat(store.read()).isEqualTo(SecretRead.Absent)
  }

  @Test
  fun `ciphertext the Keystore cannot decrypt reads as unavailable`() {
    seedCiphertext()

    assertThat(store.read()).isEqualTo(SecretRead.Unavailable)
  }

  @Test
  fun `a failed write reports false`() {
    assertThat(store.write("secret")).isFalse()
    assertThat(store.read()).isEqualTo(SecretRead.Absent)
  }

  @Test
  fun `delete removes the ciphertext`() {
    seedCiphertext()

    store.delete()

    assertThat(store.read()).isEqualTo(SecretRead.Absent)
  }
}
