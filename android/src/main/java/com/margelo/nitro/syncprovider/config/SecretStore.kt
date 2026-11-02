package com.margelo.nitro.syncprovider.config

/** Outcome of reading the stored secret. */
internal sealed interface SecretRead {
  /** Deliberately not a data class: a generated `toString` would reveal the value. */
  class Present(val value: String) : SecretRead

  /** Nothing is stored. */
  data object Absent : SecretRead

  /** Something is stored but cannot be decrypted, now or ever. */
  data object Unavailable : SecretRead
}

/**
 * Encrypted-at-rest storage for one secret value: the serialized `defaultHeaders`.
 *
 * An interface because neither Robolectric nor a plain JVM has a real Android
 * Keystore. Implementations never log the value, and report failures through their
 * return values rather than by throwing.
 */
internal interface SecretStore {
  fun read(): SecretRead

  /** @return `false` when the value could not be stored. */
  fun write(value: String): Boolean

  fun delete()
}
