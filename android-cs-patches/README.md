# Android 17 Bluetooth Channel Sounding Patches for Unbonded ISO/IEC 18013-5 BLE

This directory contains AOSP patches, prebuilt Mainline APEX modules (`com.android.bt.apex` and
`com.android.uwb.apex`), and the compile-time `framework-ranging.stubs.jar` SDK stub JAR enabling
**unbonded Bluetooth Channel Sounding (CS)** over ephemeral ISO/IEC 18013-5 BLE connections using an
out-of-band 16-byte Long Term Key (`BLELTK`).

---

## Why These Patches Are Needed

Stock Android 16 (API 36) and Android 17 (API 37) gate `RangingManager` (`BLE_CS`) on a persistent
OS-level Bluetooth bond (`BluetoothDevice.BOND_BONDED`), failing with `REASON_UNSUPPORTED` (code `3`)
on unbonded ephemeral ISO/IEC 18013-5 BLE links:

1. **`packages/modules/Uwb` (`com.android.uwb`):**
   - `BleCsRangingParams` had no parameter for passing an out-of-band ephemeral LTK.
   - `ConfigurationUtils` and `RangingInjector` emitted a `NoOpRangingAdapter` whenever the local
     device configured `RangingPreference.DEVICE_ROLE_RESPONDER`.
2. **`packages/modules/Bluetooth` (`com.android.bt`):**
   - `DistanceMeasurementManager` rejected unbonded `BluetoothDevice` instances (`checkLinkRequirements`
     and `getBondState(device) != BOND_BONDED`).
   - BTM LE Security (`btm_ble_sec.cc` / `btm_sec.cc`) had no path to initiate `HCI_LE_Enable_Encryption`
     (`EDIV = 0`, `Rand = 0`) on Central or answer `HCI_LE_Long_Term_Key_Request` (`EDIV = 0`,
     `Rand = 0`) on Peripheral using an app-supplied ephemeral 128-bit LTK without creating a bond.
   - GATT Ranging Service (`RAS`, UUID `0x185B`) requires `ENCRYPTED` characteristic permissions
     with a 16-byte key (`gatt_auth.cc` / `gatt_utils.cc` previously checked `BTM_IsBonded()`).
   - Bidirectional CS Initiator sessions could collide on `Config_ID = 0` (`distance_measurement_manager_impl.cc`).

### Summary of What the Patches Add

* **Public Ranging API (`BleCsRangingParams`):**
  * `BleCsRangingParams.Builder#setEphemeralLtk(@Nullable byte[] ephemeralLtk)` (validates 16 bytes)
  * `BleCsRangingParams#getEphemeralLtk()`
  * Guarded by exported flag `com.android.ranging.flags.ranging_cs_ephemeral_ltk` (compiled `ENABLED`).
  * Supports both `DEVICE_ROLE_INITIATOR` (encrypts/accepts encryption with `BLELTK`, opens RAS, and
    runs CS procedures) and `DEVICE_ROLE_RESPONDER` (provisions `BLELTK` in BTM so the link encrypts
    and RAS Server serves CS subevent data without initiating local CS procedures).
  * Keys `CsSession` by `(peerAddress, deviceRole)` and `DistanceMeasurementManager` sessions by
    `(BluetoothDevice, responderOnly)` so simultaneous bidirectional CS works cleanly.
* **System Bluetooth API & Stack (`ChannelSoundingParams` + BTM / GATT / GD):**
  * `@SystemApi` `ChannelSoundingParams.Builder#setEphemeralLtk(byte[])` and `#setResponderOnly(boolean)`
    guarded by `com.android.bluetooth.flags.channel_sounding_ephemeral_ltk` (compiled `ENABLED`).
  * **BLE Central:** Calls `btm_ble_start_encrypt` (`HCI_LE_Enable_Encryption` with `EDIV = 0`,
    `Rand = 0`, and the 16-byte `BLELTK`); `DistanceMeasurementInterfaceImpl` waits for
    `BTM_IsEncrypted` before starting GD CS and connecting `RasClient`.
  * **BLE Peripheral:** Replies to `HCI_LE_Long_Term_Key_Request` (`EDIV = 0`, `Rand = 0`) with
    `BLELTK`, including a 1,500 ms race-free hold timer (`pending_zero_ediv_ltk_req`) in case the
    remote Central initiates `LL_ENC_REQ` a few milliseconds before the local app's Binder call lands.
  * **GATT RAS & Ephemeral Cleanup:** Satisfies GATT `is_link_key_known` and `le_enc_key_size = 16`
    checks strictly for the active LE connection without reporting `BOND_BONDED` to Android or
    persisting keys to NVRAM; scrubs `ephemeral_oob_ltk` with `OPENSSL_cleanse` when the CS session
    stops or the LE ACL disconnects.
  * **Bidirectional `Config_ID` Partitioning:** Partitions preferred CS `Config_ID`s by LE ACL role
    (`0..1` for Central, `2..3` for Peripheral).

### How Multipaz Uses This

* `:multipaz` links against `android-cs-patches/framework-ranging.stubs.jar` via **`compileOnly`** in
  `multipaz/build.gradle.kts` (never packaged into the AAR or APK).
* `SessionEncryption.getBleLtk()` derives a `SecretKey` holding the 16-byte `BLELTK` via
  `HKDF-SHA-256` from the ECKA-DH shared secret `Z_AB`, `salt = SHA-256(SessionTranscriptBytes)`,
  and `info = "BLELTK"`.
* Once the initial `SessionMessage` establishes `SessionTranscript` on both peers,
  `MdocTransport.startBleChannelSounding(bleLtk, onResult)` delegates to
  `BleCentralHal.startChannelSounding` / `BlePeripheralHal.startChannelSounding` (so external USB
  HCI adapters via `multipaz-bthci` do not touch Android's built-in `RangingManager`), and
  `BleCentralHalAndroid` / `BlePeripheralHalAndroid` pass `BLELTK` to
  `BleCsRangingParams.Builder#setEphemeralLtk(byte[])` and deliver `BleChannelSoundingResult`
  distance measurements back to the caller (scrubbing temporary byte arrays with `secureZero()` and
  falling back gracefully on `NoSuchMethodError` if running on an unpatched stock Android 16/17
  build).

---

## Directory Contents

Built from AOSP **`android17-release`** (`aosp_arm64-cp2a-userdebug`, `BUILD_ID=CP2A.260605.016`):

| File | Size | SHA-256 | Description |
| :--- | :---: | :--- | :--- |
| `com.android.bt.apex` | 26 MB | `94ea74166d9d387da72379d08963cac8b42904b354bf0afe61f14f35f2210084` | Patched Bluetooth Mainline APEX (`arm64`) |
| `com.android.uwb.apex` | 6.9 MB | `4aca9aea185e256691de1dfbdf0f6b886f360fdaea38733d401146681ed74192` | Patched UWB / Ranging Mainline APEX (`arm64`) |
| `framework-ranging.stubs.jar` | 61 KB | `696c5e682148a2fc6084cb511156a06c481c90991964fda7ed3aa3eb13d7bcc2` | Compile-time SDK stub JAR (`compileOnly`) with `BleCsRangingParams#setEphemeralLtk` / `getEphemeralLtk` |
| `packages-modules-Uwb-ephemeral-ltk.patch` | 19 KB | `dc7b0d4e9f4737bab1786048d8cbfebf430b9df7cda2619a9144f6b3a93d0e7b` | `git diff` for AOSP `packages/modules/Uwb` |
| `packages-modules-Bluetooth-ephemeral-ltk.patch` | 53 KB | `a54e7bea3c24123bb38bac3eded75a095b99bca326d2fcdaa1932d83a0298257` | `git diff` for AOSP `packages/modules/Bluetooth` |
| `build-release-ephemeral-ltk.patch` | 2.1 KB | `89024436ed3af064c2bcd1e4a5ff7563ea9dd6a11b2ce22e086a03ff97b101e7` | `git diff` for AOSP `build/release` (`cp2a` & `trunk_staging` flag values) |

---

## Installing on a Verified Boot ORANGE (`userdebug`) Device

### Prerequisites

1. **Hardware:** An `arm64` Android device with Bluetooth 6.0 Channel Sounding hardware support
   (`android.hardware.bluetooth.le.channel_sounding`, e.g., Pixel 10 series).
2. **OS Build:** Android 17 **`userdebug`** (or `eng`) build (`ro.debuggable=1`).
   - Recommended match: Android 17 `CP2A` (`android17-release`), which matches the exact
     bootclasspath API surface of these APEXes.
   - Android 17 QPR1 / QPR2 `userdebug` builds can also be tested non-destructively (via `adb remount`
     `overlayfs`), provided `system_server` on that QPR build does not invoke a newer QPR-only
     `@SystemApi` absent from `CP2A` (if it does, see [Reverting to Stock](#4-reverting-to-stock)).
   - **Note:** `adb install *.apex` will **not** work on retail/Google builds even with Verified Boot
     ORANGE because `apexd` checks `/data/apex/active/` updates against Google's Mainline signing key
     pinned in `/system/apex/com.google.android.{bt,uwb}.capex`. Replacing the preinstalled APEXes in
     `/system/apex/` via `adb remount` makes `apexd` trust the AOSP APEX key directly.
3. **Bootloader:** Unlocked (`Verified Boot = ORANGE`).

### 1. Disable `dm-verity` and Enable `/system` Remount

Connect the `userdebug` device over USB and disable `dm-verity`:

```bash
adb root
adb disable-verity
adb reboot
adb wait-for-device
adb root
adb remount
```

*(Alternative if disabling verity from `fastboot` using your OS build's `vbmeta.img`:)*
```bash
fastboot --disable-verity --disable-verification flash vbmeta vbmeta.img
fastboot reboot
adb wait-for-device && adb root && adb remount
```

### 2. Replace the Bluetooth and UWB/Ranging Mainline APEXes

Remove both the factory `.capex`/`.apex` packages in `/system/apex/` (avoiding duplicate-APEX errors
in `apexd`) and any Play System Update copies cached under `/data/apex/`, then push the patched APEXes:

```bash
# From the repository root:
adb root && adb remount

adb shell "rm -f /system/apex/com.google.android.bt* /system/apex/com.android.bt* \
                 /system/apex/com.google.android.uwb* /system/apex/com.android.uwb* && \
           rm -rf /data/apex/active/com.*bt* /data/apex/active/com.*uwb* \
                  /data/apex/decompressed/com.*bt* /data/apex/decompressed/com.*uwb*"

adb push android-cs-patches/com.android.bt.apex /system/apex/com.android.bt.apex
adb push android-cs-patches/com.android.uwb.apex /system/apex/com.android.uwb.apex

adb shell "chmod 644 /system/apex/com.android.bt.apex /system/apex/com.android.uwb.apex && \
           restorecon -RF /system/apex"

adb reboot
```

### 3. Verify After Reboot

Once the device boots back up:

```bash
adb wait-for-device

# 1. Confirm both patched /system/apex modules are mounted and active (Factory: true):
adb shell cmd apexservice getActivePackages | grep -E "com\.android\.(bt|uwb)"

# 2. Confirm Bluetooth is enabled and Channel Sounding hardware feature is present:
adb shell pm list features | grep channel_sounding
adb shell cmd bluetooth_manager wait-for-state:STATE_ON

# 3. Install Multipaz testapp and watch live Channel Sounding + BTM encryption logs:
./gradlew :samples:testapp:installDebug
adb logcat -s BleChannelSounding CsSession GattDistanceMeasurementManager ble_sec
```

Perform an unbonded proximity transaction (Device QR, Reader QR, or NFC Handover / CCE with BLE)
between two patched devices **without pairing them in Bluetooth Settings**. In `logcat`, you should see:
* `ble_sec`: Central starting LE encryption (`EDIV = 0, Rand = 0`) and Peripheral replying to the
  zero-EDIV LTK request with the ephemeral OOB LTK.
* `CsSession` / `BleChannelSounding`: `Ranging session started` followed by live
  `Bluetooth Channel Sounding (Initiator) distance: X.XX m` measurements.

### 4. Reverting to Stock

Because `adb remount` writes to an `overlayfs` scratch backing store without altering the underlying
read-only `/system` partition, reverting to the device's original factory APEXes only takes:

```bash
adb root
adb enable-verity
adb reboot
```

---

## Rebuilding the APEXes and Stub JAR from AOSP Source

To reproduce these artifacts from public AOSP (`android17-release`) or port the patches onto an
internal QPR branch:

```bash
# 1. Sync AOSP android17-release
repo init -u https://android.googlesource.com/platform/manifest -b android17-release --depth=1
repo sync -c -j8 --no-tags

# 2. Apply the three patches
git -C packages/modules/Uwb apply /path/to/android-cs-patches/packages-modules-Uwb-ephemeral-ltk.patch
git -C packages/modules/Bluetooth apply /path/to/android-cs-patches/packages-modules-Bluetooth-ephemeral-ltk.patch
git -C build/release apply /path/to/android-cs-patches/build-release-ephemeral-ltk.patch

# 3. Build only the two Mainline APEXes (using Ninja locally rather than RBE Siso)
export SOONG_NINJA=ninja
export SOONG_ONLY=true
export SOONG_INCREMENTAL_ANALYSIS=false
source build/envsetup.sh
lunch aosp_arm64-cp2a-userdebug
m com.android.bt com.android.uwb

# 4. Copy the built APEXes and SDK stub JAR:
#   out/soong/.intermediates/packages/modules/Bluetooth/apex/com.android.bt/android_common_scs_com.android.bt/com.android.bt.apex
#   out/soong/.intermediates/packages/modules/Uwb/apex/com.android.uwb/android_common_com.android.uwb/com.android.uwb.apex
#   out/soong/.intermediates/packages/modules/Uwb/ranging/framework/framework-ranging.stubs.from-text/android_common/framework-ranging.stubs.from-text/framework-ranging.stubs.from-text.jar
```
