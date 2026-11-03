
private fun showAbout() {

    val navy = Color.rgb(28, 48, 68)
    val gold = Color.rgb(177, 145, 83)
    val dark = Color.rgb(48, 58, 68)
    val muted = Color.rgb(112, 122, 132)
    val white = Color.WHITE
    val border = Color.rgb(232, 235, 238)

    fun roundedBackground(
        color: Int,
        strokeColor: Int = color,
        radius: Float = 16f
    ): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius.toInt()).toFloat()
            setStroke(dp(1), strokeColor)
        }
    }

    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding(dp(18), dp(20), dp(18), dp(22))
        setBackgroundColor(white)
    }

    // عنوان اصلی
    val brand = TextView(this).apply {
        text = "CRYSTAL VISION"
        textSize = 12f
        letterSpacing = 0.18f
        setTextColor(gold)
        gravity = Gravity.CENTER
        typeface = android.graphics.Typeface.create(
            "sans-serif-medium",
            android.graphics.Typeface.NORMAL
        )
    }

    content.addView(
        brand,
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(25)
        )
    )

    val title = TextView(this).apply {
        text = "سامانه هوشمند\nشمارش کیسه‌های قند"
        textSize = 23f
        setTextColor(navy)
        gravity = Gravity.CENTER
        setLineSpacing(dp(4).toFloat(), 1f)
        typeface = android.graphics.Typeface.create(
            "sans-serif",
            android.graphics.Typeface.BOLD
        )
        setPadding(0, dp(5), 0, dp(5))
    }

    content.addView(title)

    val subtitle = TextView(this).apply {
        text = "راهکاری هوشمند برای دقت و سرعت در صنعت"
        textSize = 13f
        setTextColor(muted)
        gravity = Gravity.CENTER
        setPadding(0, dp(5), 0, dp(20))
        typeface = android.graphics.Typeface.create(
            "sans-serif",
            android.graphics.Typeface.NORMAL
        )
    }

    content.addView(subtitle)

    // بخش لوگوها
    val logos = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    fun createLogoCard(
        imageResource: Int,
        caption: String
    ): LinearLayout {

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = roundedBackground(white, border)
        }

        val image = ImageView(this).apply {
            setImageResource(imageResource)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = caption
            setPadding(dp(5), dp(4), dp(5), dp(4))
        }

        card.addView(
            image,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(85)
            )
        )

        val label = TextView(this).apply {
            text = caption
            textSize = 12f
            setTextColor(navy)
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create(
                "sans-serif-medium",
                android.graphics.Typeface.NORMAL
            )
            setPadding(0, dp(8), 0, 0)
        }

        card.addView(label)

        return card
    }

    logos.addView(
        createLogoCard(
            R.drawable.university_logo,
            "دانشگاه آزاد اسلامی"
        ),
        LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ).apply {
            marginEnd = dp(6)
        }
    )

    logos.addView(
        createLogoCard(
            R.drawable.factory_logo,
            "کارخانه قند حکمتان"
        ),
        LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ).apply {
            marginStart = dp(6)
        }
    )

    content.addView(
        logos,
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    )

    // معرفی مجموعه
    fun addSectionTitle(value: String) {
        val text = TextView(this).apply {
            this.text = value
            textSize = 15f
            setTextColor(navy)
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            typeface = android.graphics.Typeface.create(
                "sans-serif",
                android.graphics.Typeface.BOLD
            )
            setPadding(0, dp(20), 0, dp(9))
        }
        content.addView(text)
    }

    fun addInfoCard(value: String) {
        val card = TextView(this).apply {
            text = value
            textSize = 14f
            setTextColor(dark)
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = roundedBackground(
                Color.rgb(248, 249, 250),
                border
            )
            typeface = android.graphics.Typeface.create(
                "sans-serif-medium",
                android.graphics.Typeface.NORMAL
            )
        }

        content.addView(
            card,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    addSectionTitle("معرفی پروژه")

    addInfoCard("دانشگاه آزاد اسلامی واحد همدان")

    val organization = TextView(this).apply {
        text = "هسته فناور کریستال‌بین"
        textSize = 14f
        setTextColor(navy)
        gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(13), dp(14), dp(13))
        background = roundedBackground(
            Color.rgb(250, 247, 240),
            Color.rgb(235, 225, 205)
        )
        typeface = android.graphics.Typeface.create(
            "sans-serif",
            android.graphics.Typeface.BOLD
        )
    }

    content.addView(
        organization,
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(8)
        }
    )

    // اعضای تیم
    addSectionTitle("تیم توسعه پروژه")

    fun addMember(
        number: String,
        name: String,
        role: String? = null
    ) {

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBackground(white, border)
        }

        val numberView = TextView(this).apply {
            text = number
            textSize = 13f
            setTextColor(gold)
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = roundedBackground(
                Color.rgb(250, 247, 240),
                Color.rgb(235, 225, 205),
                10f
            )
        }

        row.addView(
            numberView,
            LinearLayout.LayoutParams(dp(34), dp(34))
        )

        val nameColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }

        val nameView = TextView(this).apply {
            text = name
            textSize = 15f
            setTextColor(navy)
            gravity = Gravity.RIGHT
            typeface = android.graphics.Typeface.create(
                "sans-serif-medium",
                android.graphics.Typeface.NORMAL
            )
        }

        nameColumn.addView(nameView)

        if (role != null) {
            val roleView = TextView(this).apply {
                text = role
                textSize = 12f
                setTextColor(muted)
                gravity = Gravity.RIGHT
                setPadding(0, dp(4), 0, 0)
            }
            nameColumn.addView(roleView)
        }

        row.addView(
            nameColumn,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        content.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(7)
            }
        )
    }

    addMember("۰۱", "محمدمهدی شیرمحمدی", "استاد راهنما")
    addMember("۰۲", "زهرا ده‌ده‌سی")
    addMember("۰۳", "علی قاسمی")
    addMember("۰۴", "سینا بالائی")

    // پایانی صفحه
    val footer = TextView(this).apply {
        text = "CRYSTAL VISION  •  SMART INDUSTRIAL SOLUTION"
        textSize = 10f
        letterSpacing = 0.05f
        setTextColor(muted)
        gravity = Gravity.CENTER
        setPadding(0, dp(20), 0, dp(5))
    }

    content.addView(footer)

    val scroll = ScrollView(this).apply {
        isFillViewport = true
        setBackgroundColor(white)
        addView(content)
    }

    val dialog = AlertDialog.Builder(this)
        .setView(scroll)
        .setPositiveButton("بستن", null)
        .create()

    dialog.show()

    dialog.window?.apply {
        setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(white)
        )
        setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            -2
        )
    }

    dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
        setTextColor(navy)
        textSize = 14f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
}
