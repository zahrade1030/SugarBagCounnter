
package com.vea.cementbagcounter

import android.app.Activity
import android.os.Bundle
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.*
import android.graphics.drawable.GradientDrawable

class LicenseActivity : Activity() {

    private val gold = Color.rgb(212, 175, 55)
    private val backgroundColor = Color.rgb(12, 12, 12)
    private val card = Color.rgb(27, 27, 27)
    private val white = Color.WHITE
    private val gray = Color.rgb(160, 160, 160)
    private val red = Color.rgb(235, 75, 75)
    private val green = Color.rgb(75, 210, 120)

    private lateinit var deviceIdText: TextView
    private lateinit var licenseInput: EditText
    private lateinit var statusText: TextView
    private lateinit var activateButton: Button

    private val isEnglish: Boolean
        get() = getSharedPreferences("app_settings", MODE_PRIVATE)
            .getString("language", "fa") == "en"

    private fun tr(fa: String, en: String): String {
        return if (isEnglish) en else fa
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (LicenseManager.isActivated(this)) {
            openMainActivity()
            return
        }

        createUI()
    }

    private fun createUI() {

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setPadding(28, 32, 28, 32)
        root.setBackgroundColor(backgroundColor)

        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.addView(root)

        // Language button

        val languageButton = Button(this)
        languageButton.text = if (isEnglish) "فارسی" else "English"
        languageButton.setTextColor(gold)
        languageButton.background = roundedBackground(
            Color.rgb(35, 35, 35),
            12
        )

        val languageParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            45
        )

        languageParams.gravity = Gravity.END

        root.addView(languageButton, languageParams)

        languageButton.setOnClickListener {

            val newLanguage = if (isEnglish) "fa" else "en"

            getSharedPreferences(
                "app_settings",
                MODE_PRIVATE
            ).edit()
                .putString("language", newLanguage)
                .apply()

            recreate()
        }

        addSpace(root, 15)

        // Logo / Brand

        val title = TextView(this)
        title.text = "CRYSTAL.BIN"
        title.textSize = 32f
        title.setTextColor(gold)
        title.gravity = Gravity.CENTER
        title.typeface = Typeface.DEFAULT_BOLD

        root.addView(title)

        addSpace(root, 8)

        val subtitle = TextView(this)
        subtitle.text = tr(
            "شمارشگر صنعتی کیسه قند",
            "Industrial Sugar Bag Counter"
        )
        subtitle.textSize = 17f
        subtitle.setTextColor(white)
        subtitle.gravity = Gravity.CENTER

        root.addView(subtitle)

        addSpace(root, 10)

        val description = TextView(this)
        description.text = tr(
            "سیستم هوشمند شمارش و مدیریت تولید",
            "Intelligent Production Counting System"
        )
        description.textSize = 13f
        description.setTextColor(gray)
        description.gravity = Gravity.CENTER

        root.addView(description)

        addSpace(root, 30)

        // Main card

        val cardView = LinearLayout(this)
        cardView.orientation = LinearLayout.VERTICAL
        cardView.setPadding(22, 24, 22, 24)
        cardView.background = roundedBackground(card, 22)

        root.addView(
            cardView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // Heading

        val heading = TextView(this)
        heading.text = tr(
            "فعال‌سازی نرم‌افزار",
            "Software Activation"
        )
        heading.textSize = 21f
        heading.setTextColor(gold)
        heading.gravity = Gravity.CENTER
        heading.typeface = Typeface.DEFAULT_BOLD

        cardView.addView(heading)

        addSpace(cardView, 10)

        val activationDescription = TextView(this)
        activationDescription.text = tr(
            "برای استفاده از نرم‌افزار، لایسنس اختصاصی دستگاه را وارد کنید.",
            "Enter your device-specific license to unlock the software."
        )
        activationDescription.textSize = 13f
        activationDescription.setTextColor(gray)
        activationDescription.gravity = Gravity.CENTER

        cardView.addView(activationDescription)

        addSpace(cardView, 25)

        // Device ID

        val deviceLabel = TextView(this)
        deviceLabel.text = tr(
            "شناسه اختصاصی دستگاه",
            "Device ID"
        )
        deviceLabel.setTextColor(gold)
        deviceLabel.textSize = 14f
        deviceLabel.typeface = Typeface.DEFAULT_BOLD

        cardView.addView(deviceLabel)

        addSpace(cardView, 8)

        deviceIdText = TextView(this)
        deviceIdText.text = LicenseManager.getDeviceId(this)
        deviceIdText.textSize = 13f
        deviceIdText.setTextColor(white)
        deviceIdText.gravity = Gravity.CENTER
        deviceIdText.setPadding(12, 16, 12, 16)
        deviceIdText.typeface = Typeface.MONOSPACE
        deviceIdText.background = roundedBackground(
            Color.rgb(40, 40, 40),
            12
        )

        cardView.addView(deviceIdText)

        addSpace(cardView, 12)

        // Copy Device ID

        val copyButton = createButton(
            tr(
                "کپی شناسه دستگاه",
                "Copy Device ID"
            ),
            false
        )

        cardView.addView(
            copyButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                52
            )
        )

        copyButton.setOnClickListener {

            val clipboard = getSystemService(
                Context.CLIPBOARD_SERVICE
            ) as ClipboardManager

            clipboard.setPrimaryClip(
                ClipData.newPlainText(
                    "Device ID",
                    deviceIdText.text.toString()
                )
            )

            Toast.makeText(
                this,
                tr(
                    "شناسه دستگاه کپی شد",
                    "Device ID copied"
                ),
                Toast.LENGTH_SHORT
            ).show()
        }

        addSpace(cardView, 28)

        // License input

        val licenseLabel = TextView(this)
        licenseLabel.text = tr(
            "کد لایسنس",
            "License Key"
        )
        licenseLabel.setTextColor(gold)
        licenseLabel.textSize = 14f
        licenseLabel.typeface = Typeface.DEFAULT_BOLD

        cardView.addView(licenseLabel)

        addSpace(cardView, 8)

        licenseInput = EditText(this)
        licenseInput.hint = tr(
            "کد فعال‌سازی را وارد کنید",
            "Enter your activation code"
        )
        licenseInput.setTextColor(white)
        licenseInput.setHintTextColor(gray)
        licenseInput.textSize = 12f
        licenseInput.setPadding(16, 18, 16, 18)
        licenseInput.background = roundedBackground(
            Color.rgb(40, 40, 40),
            12
        )

        licenseInput.isSingleLine = false
        licenseInput.minLines = 3
        licenseInput.maxLines = 6

        cardView.addView(
            licenseInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        addSpace(cardView, 22)

        // Activate button

        activateButton = createButton(
            tr(
                "فعال‌سازی و ورود به برنامه",
                "Activate & Enter App"
            ),
            true
        )

        cardView.addView(
            activateButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                58
            )
        )

        activateButton.setOnClickListener {
            activateLicense()
        }

        addSpace(cardView, 18)

        // Status

        statusText = TextView(this)
        statusText.text = ""
        statusText.textSize = 14f
        statusText.gravity = Gravity.CENTER
        statusText.setTextColor(red)
        statusText.setPadding(8, 12, 8, 12)

        cardView.addView(statusText)

        addSpace(root, 25)

        // Footer

        val footer = TextView(this)
        footer.text = "CRYSTAL.BIN"
        footer.textSize = 14f
        footer.setTextColor(gold)
        footer.gravity = Gravity.CENTER
        footer.typeface = Typeface.DEFAULT_BOLD

        root.addView(footer)

        addSpace(root, 5)

        val footerDescription = TextView(this)
        footerDescription.text = tr(
            "سیستم هوشمند صنعتی",
            "Industrial Vision System"
        )
        footerDescription.textSize = 11f
        footerDescription.setTextColor(gray)
        footerDescription.gravity = Gravity.CENTER

        root.addView(footerDescription)

        setContentView(scroll)
    }

    private fun activateLicense() {

        val code = licenseInput.text.toString().trim()

        statusText.text = ""
        statusText.setTextColor(red)

        if (code.isEmpty()) {

            statusText.text = tr(
                "لطفاً کد فعال‌سازی را وارد کنید",
                "Please enter your activation code"
            )

            return
        }

        setLoading(true)

        val success = try {
            LicenseManager.activate(this, code)
        } catch (e: Exception) {
            false
        }

        setLoading(false)

        if (success) {

            statusText.setTextColor(green)

            statusText.text = tr(
                "فعال‌سازی موفق بود",
                "Activation successful"
            )

            Toast.makeText(
                this,
                tr(
                    "فعال‌سازی موفق بود",
                    "Activation successful"
                ),
                Toast.LENGTH_LONG
            ).show()

            openMainActivity()

        } else {

            statusText.setTextColor(red)

            statusText.text = tr(
                "کد نامعتبر است، منقضی شده یا متعلق به دستگاه دیگری است",
                "Invalid, expired, or belongs to another device"
            )
        }
    }

    private fun setLoading(loading: Boolean) {

        activateButton.isEnabled = !loading

        activateButton.text = if (loading) {
            tr(
                "در حال بررسی لایسنس...",
                "Verifying license..."
            )
        } else {
            tr(
                "فعال‌سازی و ورود به برنامه",
                "Activate & Enter App"
            )
        }

        activateButton.alpha = if (loading) 0.6f else 1f
    }

    private fun createButton(
        text: String,
        primary: Boolean
    ): Button {

        return Button(this).apply {

            this.text = text

            textSize = 14f

            typeface = Typeface.DEFAULT_BOLD

            // FIX: Explicit color avoids background name/type conflict.
            setTextColor(
                if (primary) Color.rgb(12, 12, 12) else gold
            )

            background = roundedBackground(
                if (primary) gold else Color.rgb(40, 40, 40),
                14
            )

            isAllCaps = false
        }
    }

    private fun openMainActivity() {

        val intent = Intent(
            this,
            MainActivity::class.java
        )

        intent.flags =
            Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TASK

        startActivity(intent)

        finish()
    }

    private fun addSpace(
        parent: LinearLayout,
        height: Int
    ) {

        val space = View(this)

        parent.addView(
            space,
            LinearLayout.LayoutParams(
                1,
                height
            )
        )
    }

    private fun roundedBackground(
        color: Int,
        radius: Int
    ): GradientDrawable {

        return GradientDrawable().apply {

            setColor(color)

            cornerRadius = radius.toFloat()

            setStroke(
                1,
                gold
            )
        }
    }
}
