package com.vea.cementbagcounter

import android.app.Activity
import android.os.Bundle
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.*
import android.graphics.drawable.GradientDrawable

class LicenseActivity : Activity() {

    private val gold = Color.rgb(212, 175, 55)
    private val background = Color.rgb(12, 12, 12)
    private val card = Color.rgb(27, 27, 27)
    private val white = Color.WHITE
    private val gray = Color.rgb(160, 160, 160)

    private lateinit var deviceIdText: TextView
    private lateinit var licenseInput: EditText
    private lateinit var statusText: TextView

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
        root.setPadding(32, 40, 32, 40)
        root.setBackgroundColor(background)

        val scroll = ScrollView(this)
        scroll.addView(root)

        val title = TextView(this)
        title.text = "VEA"
        title.textSize = 34f
        title.setTextColor(gold)
        title.gravity = Gravity.CENTER
        title.typeface = Typeface.DEFAULT_BOLD

        root.addView(title)

        val subtitle = TextView(this)
        subtitle.text = "شمارشگر صنعتی کیسه قند"
        subtitle.textSize = 18f
        subtitle.setTextColor(white)
        subtitle.gravity = Gravity.CENTER

        root.addView(subtitle)

        addSpace(root, 30)

        val cardView = LinearLayout(this)
        cardView.orientation = LinearLayout.VERTICAL
        cardView.setPadding(24, 24, 24, 24)
        cardView.background = roundedBackground(card, 20)

        root.addView(
            cardView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val heading = TextView(this)
        heading.text = "فعال‌سازی نرم‌افزار"
        heading.textSize = 20f
        heading.setTextColor(gold)
        heading.gravity = Gravity.CENTER
        heading.typeface = Typeface.DEFAULT_BOLD

        cardView.addView(heading)

        addSpace(cardView, 20)

        val deviceLabel = TextView(this)
        deviceLabel.text = "شناسه اختصاصی دستگاه"
        deviceLabel.setTextColor(gray)
        deviceLabel.textSize = 14f

        cardView.addView(deviceLabel)

        addSpace(cardView, 8)

        deviceIdText = TextView(this)
        deviceIdText.text = LicenseManager.getDeviceId(this)
        deviceIdText.textSize = 13f
        deviceIdText.setTextColor(white)
        deviceIdText.gravity = Gravity.CENTER
        deviceIdText.setPadding(12, 16, 12, 16)
        deviceIdText.background = roundedBackground(
            Color.rgb(40, 40, 40),
            10
        )

        cardView.addView(deviceIdText)

        addSpace(cardView, 10)

        val copyButton = Button(this)
        copyButton.text = "کپی شناسه دستگاه"

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
                "شناسه کپی شد",
                Toast.LENGTH_SHORT
            ).show()
        }

        cardView.addView(copyButton)

        addSpace(cardView, 24)

        val licenseLabel = TextView(this)
        licenseLabel.text = "کد فعال‌سازی"
        licenseLabel.setTextColor(gray)
        licenseLabel.textSize = 14f

        cardView.addView(licenseLabel)

        addSpace(cardView, 8)

        licenseInput = EditText(this)
        licenseInput.hint = "کد لایسنس را وارد کنید"
        licenseInput.setTextColor(white)
        licenseInput.setHintTextColor(gray)
        licenseInput.textSize = 14f
        licenseInput.setPadding(16, 18, 16, 18)
        licenseInput.setBackgroundColor(
            Color.rgb(45, 45, 45)
        )

        cardView.addView(
            licenseInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        addSpace(cardView, 20)

        val activateButton = Button(this)
        activateButton.text = "فعال‌سازی"

        activateButton.setOnClickListener {
            activateLicense()
        }

        cardView.addView(
            activateButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                55
            )
        )

        addSpace(cardView, 16)

        statusText = TextView(this)
        statusText.text = ""
        statusText.textSize = 14f
        statusText.gravity = Gravity.CENTER
        statusText.setTextColor(Color.RED)

        cardView.addView(statusText)

        addSpace(root, 20)

        val footer = TextView(this)
        footer.text = "VEA • Industrial Vision System"
        footer.textSize = 12f
        footer.setTextColor(gray)
        footer.gravity = Gravity.CENTER

        root.addView(footer)

        setContentView(scroll)
    }

    private fun activateLicense() {

        val code = licenseInput.text.toString().trim()

        if (code.isEmpty()) {

            statusText.text = "کد فعال‌سازی را وارد کنید"

            return
        }

        val success = LicenseManager.activate(
            this,
            code
        )

        if (success) {

            Toast.makeText(
                this,
                "فعال‌سازی موفق بود",
                Toast.LENGTH_LONG
            ).show()

            openMainActivity()

        } else {

            statusText.text =
                "کد نامعتبر است یا متعلق به دستگاه دیگری است"
        }
    }

    private fun openMainActivity() {

        startActivity(
            android.content.Intent(
                this,
                MainActivity::class.java
            )
        )

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
            setStroke(1, gold)
        }
    }
}
