require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "SyncProvider"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.homepage     = package["homepage"]
  s.license      = package["license"]
  s.authors      = package["author"]

  # BGTaskScheduler requires iOS 13.0+. We pin the floor explicitly rather than
  # relying on the Cocoapods helper because this library has a strict minimum.
  s.platforms    = { :ios => "13.0" }
  s.source       = { :git => "https://github.com/gabriel-sisjr/react-native-sync-provider.git", :tag => "#{s.version}" }

  s.source_files = [
    "ios/**/*.{swift}",
    "ios/**/*.{m,mm}",
    "cpp/**/*.{hpp,cpp}",
  ]

  # Bundle the Core Data model directly into the app bundle.
  s.resources = [
    "ios/Database/SyncProvider.xcdatamodeld",
  ]

  # The Privacy Manifest must live inside a `resource_bundle`, not `resources`.
  # Reasoning:
  #   1. React Native 0.85+ runs a Privacy-Manifest aggregator at `pod install`
  #      time (see RN's `privacy_manifest_utils.rb`). That aggregator only
  #      scans `file_accessor.resource_bundles` — it ignores `s.resources`.
  #      Putting the file under a resource bundle is the only way for our
  #      `NSPrivacyAccessedAPITypes` entries to be merged into the host app's
  #      aggregated PrivacyInfo.xcprivacy.
  #   2. If we instead listed it in `s.resources`, CocoaPods would copy it
  #      directly to `<App>.app/PrivacyInfo.xcprivacy`, colliding with the
  #      aggregated copy that the host app already owns ("Multiple commands
  #      produce …PrivacyInfo.xcprivacy" build error in Xcode 26).
  s.resource_bundles = {
    "SyncProvider_Privacy" => ["ios/PrivacyInfo.xcprivacy"]
  }

  # Apple frameworks the native implementation relies on.
  #   Foundation       — stdlib
  #   CoreData         — queue + history persistence
  #   Network          — NWPathMonitor connectivity tracking
  #   BackgroundTasks  — BGTaskScheduler (iOS 13+)
  s.frameworks = "Foundation", "CoreData", "Network", "BackgroundTasks"

  s.dependency 'React-jsi'
  s.dependency 'React-callinvoker'

  load 'nitrogen/generated/ios/SyncProvider+autolinking.rb'
  add_nitrogen_files(s)

  install_modules_dependencies(s)
end
