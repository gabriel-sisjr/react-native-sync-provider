package com.margelo.nitro.syncprovider
  
import com.facebook.proguard.annotations.DoNotStrip

@DoNotStrip
class SyncProvider : HybridSyncProviderSpec() {
  override fun multiply(a: Double, b: Double): Double {
    return a * b
  }
}
