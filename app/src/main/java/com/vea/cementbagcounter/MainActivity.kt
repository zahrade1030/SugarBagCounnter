
// --------------------------------------------------
// صفحه درباره برنامه - طراحی Premium
// --------------------------------------------------

private fun showAbout() {

    val white = Color.rgb(255, 255, 255)
    val dark = Color.rgb(35, 48, 42)
    val green = Color.rgb(35, 105, 76)
    val muted = Color.rgb(112, 125, 117)
    val border = Color.rgb(229, 235, 230)

    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(20), dp(24), dp(20), dp(24))
        setBackground(
            aboutBackground(white, 22, border)
        )
        layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    // نوار تزئینی بالای صفحه
    val accent = View(this).apply {
        setBackground(
            aboutBackground(green, 10, green)
        )
    }

    content.addView(
        accent,
        LinearLayout.LayoutParams(dp(48), dp(5)).apply {
            gravity = Gravity.CENTER
            bottomMargin = dp(18)
        }
    )

    // عنوان اصلی
    val title = TextView(this).apply {
        text = "درباره برنامه"
        textSize = 25f
        setTextColor(dark)
        gravity = Gravity.CENTER
        typeface = android.graphics.Typeface.create(
            "sans-serif",
            android.graphics.Typeface.BOLD
        )
    }

    content.addView(
        title,
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    )

    // توضیح کوتاه
    val subtitle = TextView(this).apply {
        text = "معرفی سامانه و عوامل توسعه"
        textSize = 13f
        setTextColor(muted)
        gravity = Gravity.CENTER
        setPadding(0, dp(7), 0, dp(22))
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
    }

    logos.addView(
        createAboutLogoCard(
            R.drawable.university_logo,
            "دانشگاه آزاد اسلامی",
            "واحد همدان"
        ),
        LinearLayout.LayoutParams(0, dp(155), 1f).apply {
            marginEnd = dp(6)
        }
    )

    logos.addView(
        createAboutLogoCard(
            R.drawable.factory_logo,
            "هسته فناور",
            "کریستال‌بین"
        ),
        LinearLayout.LayoutParams(0, dp(155), 1f).apply {
            marginStart = dp(6)
        }
    )

    content.addView(logos)

    // عنوان سامانه
    addAboutText(
        content,
        "سامانه هوشمند شمارش کیسه‌های قند",
        20f,
        true
    )

    // توضیح سامانه
    val description = TextView(this).apply {
        text = "طراحی و توسعه سامانه هوشمند مبتنی بر بینایی ماشین"
        textSize = 12f
        setTextColor(muted)
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(4), dp(4), dp(20))
        typeface = android.graphics.Typeface.create(
            "sans-serif",
            android.graphics.Typeface.NORMAL
        )
    }

    content.addView(description)

    // جداکننده
    val divider = View(this).apply {
        setBackgroundColor(border)
    }

    content.addView(
        divider,
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(1)
        ).apply {
            bottomMargin = dp(18)
        }
    )

    // عنوان اعضای پروژه
    val membersTitle = TextView(this).apply {
        text = "تیم توسعه پروژه"
        textSize = 17f
        setTextColor(green)
        gravity = Gravity.CENTER
        typeface = android.graphics.Typeface.create(
            "sans-serif",
            android.graphics.Typeface.BOLD
        )
    }

    content.addView(
        membersTitle,
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(13)
        }
    )

    // اعضای پروژه
    val members = listOf(
        "محمدمهدی شیرمحمدی" to "استاد راهنما",
        "زهرا ده‌ده‌سی" to "عضو تیم توسعه",
        "علی قاسمی" to "عضو تیم توسعه",
        "سینا بالائی" to "عضو تیم توسعه"
    )

    members.forEach { member ->

        content.addView(
            createAboutMemberCard(
                member.first,
                member.second
            ),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(58)
            ).apply {
                bottomMargin = dp(8)
            }
        )
    }

    // متن پایانی
    val footer = TextView(this).apply {
        text = "CRYSTAL VISION"
        textSize = 11f
        letterSpacing = 2f
        setTextColor(green)
        gravity = Gravity.CENTER
        setPadding(0, dp(17), 0, dp(3))
        typeface = android.graphics.Typeface.create(
            "sans-serif",
            android.graphics.Typeface.BOLD
        )
    }

    content.addView(footer)

    // اسکرول صفحه
    val scroll = ScrollView(this).apply {
        isFillViewport = true
        setBackgroundColor(white)
        addView(content)
    }

    // ساخت پنجره اختصاصی
    val dialog = AlertDialog.Builder(this)
        .setView(scroll)
        .setPositiveButton("بستن", null)
        .create()

    dialog.setOnShowListener {

        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(white)
        )

        dialog.window?.setLayout(
            resources.displayMetrics.widthPixels - dp(32),
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        val closeButton = dialog.getButton(
            AlertDialog.BUTTON_POSITIVE
        )

        closeButton.setTextColor(green)
        closeButton.textSize = 14f
        closeButton.typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    dialog.show()

    dialog.window?.setBackgroundDrawable(
        android.graphics.drawable.ColorDrawable(white)
    )

    dialog.window?.setLayout(
        resources.displayMetrics.widthPixels - dp(32),
        LinearLayout.LayoutParams.WRAP_CONTENT
    )
}

// --------------------------------------------------
// کارت لوگوها
// --------------------------------------------------

private fun createAboutLogoCard(
    imageResource: Int,
    heading: String,
    subheading: String
): LinearLayout {

    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(10), dp(8), dp(8))
        setBackground(
            aboutBackground(
                Color.WHITE,
                16,
                Color.rgb(229, 235, 230)
            )
        )
    }

    val image = ImageView(this).apply {
        setImageResource(imageResource)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setBackgroundColor(Color.WHITE)
        contentDescription = heading
    }

    card.addView(
        image,
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(85)
        )
    )

    val mainText = TextView(this).apply {
        text = heading
        textSize = 12f
        setTextColor(Color.rgb(40, 55, 46))
        gravity = Gravity.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    card.addView(mainText)

    val secondaryText = TextView(this).apply {
        text = subheading
        textSize = 11f
        setTextColor(Color.rgb(100, 115, 105))
        gravity = Gravity.CENTER
        setPadding(0, dp(3), 0, 0)
    }

    card.addView(secondaryText)

    return card
}

// --------------------------------------------------
// کارت اعضای پروژه
// --------------------------------------------------

private fun createAboutMemberCard(
    name: String,
    role: String
): LinearLayout {

    val card = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(13), dp(6), dp(13), dp(6))
        setBackground(
            aboutBackground(
                Color.rgb(248, 250, 248),
                12,
                Color.rgb(235, 239, 236)
            )
        )
        layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    // نشان کوچک سبز
    val indicator = View(this).apply {
        setBackground(
            aboutBackground(
                Color.rgb(49, 132, 91),
                50,
                Color.rgb(49, 132, 91)
            )
        )
    }

    card.addView(
        indicator,
        LinearLayout.LayoutParams(dp(7), dp(7)).apply {
            marginEnd = dp(10)
        }
    )

    // نام عضو
    val nameText = TextView(this).apply {
        text = name
        textSize = 14f
        setTextColor(Color.rgb(38, 50, 43))
        gravity = Gravity.CENTER_VERTICAL
        typeface = android.graphics.Typeface.create(
            "sans-serif",
            android.graphics.Typeface.BOLD
        )
    }

    card.addView(
        nameText,
        LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.MATCH_PARENT,
            1f
        )
    )

    // سمت عضو
    val roleText = TextView(this).apply {
        text = role
        textSize = 10f
        setTextColor(Color.rgb(49, 119, 83))
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(6), dp(8), dp(6))
        setBackground(
            aboutBackground(
                Color.rgb(231, 242, 234),
                8,
                Color.rgb(231, 242, 234)
            )
        )
    }

    card.addView(roleText)

    return card
}

// --------------------------------------------------
// پس زمینه کارت ها
// --------------------------------------------------

private fun aboutBackground(
    color: Int,
    radius: Int,
    strokeColor: Int
): android.graphics.drawable.GradientDrawable {

    return android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), strokeColor)
    }
}

// --------------------------------------------------
// متن های صفحه درباره برنامه
// --------------------------------------------------

private fun addAboutText(
    parent: LinearLayout,
    value: String,
    size: Float,
    bold: Boolean
) {

    val text = TextView(this).apply {

        this.text = value
        textSize = size

        setTextColor(
            if (bold) {
                Color.rgb(35, 105, 76)
            } else {
                Color.rgb(45, 55, 48)
            }
        )

        gravity = Gravity.CENTER

        setPadding(
            dp(4),
            dp(12),
            dp(4),
            dp(5)
        )

        typeface = android.graphics.Typeface.create(
            "sans-serif",
            if (bold) {
                android.graphics.Typeface.BOLD
            } else {
                android.graphics.Typeface.NORMAL
            }
        )
    }

    parent.addView(text)
}
