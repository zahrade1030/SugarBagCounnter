
package com.vea.cementbagcounter

import android.content.Context
import android.provider.Settings
import android.util.Base64
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

import org.json.JSONObject

import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.security.KeyFactory
import java.util.Calendar
import java.util.Locale

import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

object LicenseManager {

    private const val PREFS_NAME = "crystal_license_secure"

    private const val KEY_LICENSE = "license"
    private const val KEY_LAST_TIME = "last_time"
    private const val KEY_TIME_MAC = "time_mac"

    private const val PRODUCT_ID = "CRYSTAL_BAG_COUNTER"
    private const val LICENSE_VERSION = 1

    private const val CLOCK_TOLERANCE = 5 * 60 * 1000L

    private const val KEYSTORE_NAME = "AndroidKeyStore"
    private const val HMAC_ALIAS = "CRYSTAL_LICENSE_TIME_KEY"

    private const val SIGNATURE_ALGORITHM = "SHA256withRSA"
    private const val HMAC_ALGORITHM = "HmacSHA256"

    private const val DAY_MS = 86_400_000L

    private const val PUBLIC_KEY_BASE64 =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAuI3IqY+X0dkdOSmxAmxJtDLiKuiSuNRHlmtaAWBU2u01KJEnhYwLjOgNgFBsp6hAduIyuEAqvrFqqBptl8YF+Ar59HIETNfdW+SzTtrwlxoUzMH0/3JBUksTs1AE2M2pvmN2HhTLz20GaeADZxqGCzrFAVLLmzd7NIY96c988TRDK0nyBx0yGkcBOgfBuju7oXTRFEFa6q/DVkmYSX64dpFnPo2PxCbJ5H2BccrZfC/jxcRu0B1034+NMYypqbrBrh/AR9UlqI8QomN3t1ZtUQ0g/7KUpVTVm0ZUyq3AruranUCQMWKDw/OS6l7q9UkcTBuHYAFeouPH/RvvbBW7OQIDAQAB"

    enum class LicenseStatus {
        ACTIVE,
        EXPIRED,
        INVALID,
        NOT_ACTIVATED,
        DEVICE_MISMATCH,
        CLOCK_TAMPERED
    }

    data class LicenseInfo(
        val status: LicenseStatus,
        val licenseId: String,
        val customerName: String,
        val deviceId: String,
        val activationDate: Long,
        val expiryDate: Long,
        val remainingDays: Long
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    // --------------------------------------------------
    // DEVICE ID
    // --------------------------------------------------

    fun getDeviceId(context: Context): String {

        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: return "UNKNOWN"

        val raw = "$PRODUCT_ID:$androidId"

        return sha256(raw).take(32).uppercase()
    }

    private fun sha256(value: String): String {

        val digest = MessageDigest.getInstance("SHA-256")

        return digest.digest(
            value.toByteArray(Charsets.UTF_8)
        ).joinToString("") {
            "%02X".format(it)
        }
    }

    // --------------------------------------------------
    // DIGITAL SIGNATURE
    // --------------------------------------------------

    private fun getPublicKey() =
        KeyFactory.getInstance("RSA").generatePublic(
            X509EncodedKeySpec(
                Base64.decode(
                    PUBLIC_KEY_BASE64,
                    Base64.DEFAULT
                )
            )
        )

    private fun verifySignature(
        payload: ByteArray,
        signatureBytes: ByteArray
    ): Boolean {

        return try {

            val verifier = Signature.getInstance(
                SIGNATURE_ALGORITHM
            )

            verifier.initVerify(getPublicKey())
            verifier.update(payload)

            verifier.verify(signatureBytes)

        } catch (e: Exception) {
            false
        }
    }

    // --------------------------------------------------
    // LICENSE PARSING
    // --------------------------------------------------

    private data class ParsedLicense(
        val licenseId: String,
        val customerName: String,
        val deviceId: String,
        val issuedAt: Long,
        val expiresAt: Long,
        val product: String,
        val version: Int
    )

    private fun parseLicense(
        licenseCode: String
    ): ParsedLicense? {

        return try {

            val parts = licenseCode.trim().split(".")

            if (parts.size != 3) return null

            if (parts[0] != "CRYSTAL1") {
                return null
            }

            val payloadBytes = Base64.decode(
                parts[1],
                Base64.URL_SAFE or
                    Base64.NO_WRAP or
                    Base64.NO_PADDING
            )

            val signatureBytes = Base64.decode(
                parts[2],
                Base64.URL_SAFE or
                    Base64.NO_WRAP or
                    Base64.NO_PADDING
            )

            if (!verifySignature(payloadBytes, signatureBytes)) {
                return null
            }

            val json = JSONObject(
                String(payloadBytes, Charsets.UTF_8)
            )

            ParsedLicense(
                licenseId = json.getString("licenseId"),
                customerName = json.getString("customerName"),
                deviceId = json.getString("deviceId"),
                issuedAt = json.getLong("issuedAt"),
                expiresAt = json.getLong("expiresAt"),
                product = json.getString("product"),
                version = json.getInt("version")
            )

        } catch (e: Exception) {
            null
        }
    }

    // --------------------------------------------------
    // LICENSE VALIDATION
    // --------------------------------------------------

    private fun validateLicense(
        license: ParsedLicense,
        currentDeviceId: String
    ): LicenseStatus {

        if (license.version != LICENSE_VERSION) {
            return LicenseStatus.INVALID
        }

        if (license.product != PRODUCT_ID) {
            return LicenseStatus.INVALID
        }

        if (
            license.licenseId.isBlank() ||
            license.customerName.isBlank() ||
            license.deviceId.isBlank()
        ) {
            return LicenseStatus.INVALID
        }

        if (license.deviceId != currentDeviceId) {
            return LicenseStatus.DEVICE_MISMATCH
        }

        if (
            license.issuedAt <= 0 ||
            license.expiresAt <= license.issuedAt
        ) {
            return LicenseStatus.INVALID
        }

        val now = System.currentTimeMillis()

        if (license.issuedAt > now + CLOCK_TOLERANCE) {
            return LicenseStatus.INVALID
        }

        if (now >= license.expiresAt) {
            return LicenseStatus.EXPIRED
        }

        return LicenseStatus.ACTIVE
    }

    // --------------------------------------------------
    // ACTIVATION
    // --------------------------------------------------

    fun activate(
        context: Context,
        activationCode: String
    ): Boolean {

        val license = parseLicense(activationCode)
            ?: return false

        val deviceId = getDeviceId(context)

        val status = validateLicense(
            license,
            deviceId
        )

        if (status != LicenseStatus.ACTIVE) {
            return false
        }

        // First save secure time.
        // Do not store the license if secure time fails.

        if (!saveSecureTime(context)) {
            return false
        }

        // Save license only after secure time succeeds.

        return prefs(context).edit()
            .putString(KEY_LICENSE, activationCode.trim())
            .commit()
    }

    // --------------------------------------------------
    // ANDROID KEYSTORE
    // --------------------------------------------------

    private fun getOrCreateHmacKey(): SecretKey {

        val keyStore = KeyStore.getInstance(
            KEYSTORE_NAME
        )

        keyStore.load(null)

        val existing = keyStore.getKey(
            HMAC_ALIAS,
            null
        )

        if (existing is SecretKey) {
            return existing
        }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
            KEYSTORE_NAME
        )

        generator.init(
            KeyGenParameterSpec.Builder(
                HMAC_ALIAS,
                KeyProperties.PURPOSE_SIGN or
                    KeyProperties.PURPOSE_VERIFY
            )
                .setDigests(
                    KeyProperties.DIGEST_SHA256
                )
                .build()
        )

        return generator.generateKey()
    }

    private fun calculateTimeMac(
        timestamp: Long
    ): String {

        val mac = Mac.getInstance(
            HMAC_ALGORITHM
        )

        mac.init(getOrCreateHmacKey())

        val bytes = mac.doFinal(
            timestamp.toString().toByteArray(
                Charsets.UTF_8
            )
        )

        return Base64.encodeToString(
            bytes,
            Base64.NO_WRAP
        )
    }

    private fun saveSecureTime(
        context: Context
    ): Boolean {

        return try {

            val now = System.currentTimeMillis()
            val mac = calculateTimeMac(now)

            prefs(context).edit()
                .putLong(KEY_LAST_TIME, now)
                .putString(KEY_TIME_MAC, mac)
                .commit()

        } catch (e: Exception) {
            false
        }
    }

    private fun checkSecureTime(
        context: Context
    ): Boolean {

        return try {

            val p = prefs(context)

            val savedTime = p.getLong(
                KEY_LAST_TIME,
                0L
            )

            val savedMac = p.getString(
                KEY_TIME_MAC,
                null
            )

            // No previous secure time.
            if (savedTime <= 0 || savedMac == null) {
                return saveSecureTime(context)
            }

            // Verify stored timestamp integrity.

            val expectedMac = calculateTimeMac(
                savedTime
            )

            if (
                !MessageDigest.isEqual(
                    savedMac.toByteArray(Charsets.UTF_8),
                    expectedMac.toByteArray(Charsets.UTF_8)
                )
            ) {
                return false
            }

            val now = System.currentTimeMillis()

            // Detect significant clock rollback.

            if (now < savedTime - CLOCK_TOLERANCE) {
                return false
            }

            // Update secure timestamp.

            saveSecureTime(context)

        } catch (e: Exception) {
            false
        }
    }

    // --------------------------------------------------
    // STATUS
    // --------------------------------------------------

    fun getStatus(
        context: Context
    ): LicenseStatus {

        val p = prefs(context)

        val licenseCode = p.getString(
            KEY_LICENSE,
            null
        ) ?: return LicenseStatus.NOT_ACTIVATED

        val license = parseLicense(
            licenseCode
        ) ?: return LicenseStatus.INVALID

        val deviceId = getDeviceId(context)

        val validation = validateLicense(
            license,
            deviceId
        )

        if (validation != LicenseStatus.ACTIVE) {
            return validation
        }

        if (!checkSecureTime(context)) {
            return LicenseStatus.CLOCK_TAMPERED
        }

        return LicenseStatus.ACTIVE
    }

    fun isActivated(
        context: Context
    ): Boolean {

        return getStatus(context) ==
            LicenseStatus.ACTIVE
    }

    // --------------------------------------------------
    // LICENSE INFORMATION
    // --------------------------------------------------

    fun getLicenseInfo(
        context: Context
    ): LicenseInfo {

        val p = prefs(context)

        val licenseCode = p.getString(
            KEY_LICENSE,
            null
        )

        val license = licenseCode?.let {
            parseLicense(it)
        }

        val status = getStatus(context)

        val expiry = license?.expiresAt ?: 0L
        val now = System.currentTimeMillis()

        // Round up partial days.

        val remainingDays = if (expiry > now) {
            ((expiry - now + DAY_MS - 1) / DAY_MS)
                .coerceAtLeast(0)
        } else {
            0L
        }

        return LicenseInfo(
            status = status,
            licenseId = license?.licenseId ?: "",
            customerName = license?.customerName ?: "",
            deviceId = getDeviceId(context),
            activationDate = license?.issuedAt ?: 0L,
            expiryDate = expiry,
            remainingDays = remainingDays
        )
    }

    fun getRemainingDays(
        context: Context
    ): Long {

        return getLicenseInfo(context).remainingDays
    }

    fun getExpiryDate(
        context: Context
    ): Long {

        return getLicenseInfo(context).expiryDate
    }

    fun getActivationDate(
        context: Context
    ): Long {

        return getLicenseInfo(context).activationDate
    }

    // --------------------------------------------------
    // DEACTIVATION
    // --------------------------------------------------

    fun deactivate(
        context: Context
    ) {

        prefs(context).edit()
            .remove(KEY_LICENSE)
            .remove(KEY_LAST_TIME)
            .remove(KEY_TIME_MAC)
            .commit()
    }

    // --------------------------------------------------
    // STATUS TEXT
    // --------------------------------------------------

    fun getStatusText(
        context: Context
    ): String {

        val english = context
            .getSharedPreferences(
                "app_settings",
                Context.MODE_PRIVATE
            )
            .getString("language", "fa") == "en"

        return when (getStatus(context)) {

            LicenseStatus.ACTIVE ->
                if (english) "Active" else "فعال"

            LicenseStatus.EXPIRED ->
                if (english) "Expired" else "منقضی شده"

            LicenseStatus.INVALID ->
                if (english) "Invalid license" else "مجوز نامعتبر"

            LicenseStatus.NOT_ACTIVATED ->
                if (english) "Not activated" else "فعال نشده"

            LicenseStatus.DEVICE_MISMATCH ->
                if (english) "Wrong device" else "مجوز متعلق به دستگاه دیگری است"

            LicenseStatus.CLOCK_TAMPERED ->
                if (english) "Clock tampering detected" else "تغییر ساعت دستگاه شناسایی شد"
        }
    }

    // --------------------------------------------------
    // DATE FORMAT
    // --------------------------------------------------

    fun formatDate(
        timestamp: Long
    ): String {

        if (timestamp <= 0) {
            return "---"
        }

        val calendar = Calendar.getInstance()

        calendar.timeInMillis = timestamp

        val day = calendar.get(
            Calendar.DAY_OF_MONTH
        )

        val month = calendar.get(
            Calendar.MONTH
        ) + 1

        val year = calendar.get(
            Calendar.YEAR
        )

        return String.format(
            Locale.US,
            "%04d/%02d/%02d",
            year,
            month,
            day
        )
    }
}
