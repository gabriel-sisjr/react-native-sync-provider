package com.margelo.nitro.syncprovider.testutil

import com.margelo.nitro.syncprovider.config.SecretRead
import com.margelo.nitro.syncprovider.config.SecretStore

/** Stands in for the Android Keystore, which neither Robolectric nor a JVM provides. */
internal class InMemorySecretStore(
  var failReads: Boolean = false,
  var failWrites: Boolean = false,
) : SecretStore {
  var stored: String? = null
    private set

  override fun read(): SecretRead {
    if (failReads) return SecretRead.Unavailable
    return stored?.let { SecretRead.Present(it) } ?: SecretRead.Absent
  }

  override fun write(value: String): Boolean {
    if (failWrites) return false
    stored = value
    return true
  }

  override fun delete() {
    stored = null
  }
}
