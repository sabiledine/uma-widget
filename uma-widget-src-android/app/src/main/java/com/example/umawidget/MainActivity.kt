package com.example.umawidget

import android.net.Uri
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast
import android.widget.ImageView
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.google.firebase.database.FirebaseDatabase
import android.graphics.PorterDuff
import android.widget.LinearLayout

class MainActivity : AppCompatActivity() {

    private lateinit var inputPseudo: EditText
    private lateinit var inputPin: EditText
    private lateinit var inputBgUrl: EditText
    private lateinit var switchAppBg: Switch
    private lateinit var switchTextColor: Switch
    private lateinit var btnSave: Button
    private lateinit var mainScrollView: ScrollView
    private lateinit var radioGroupAlign: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        inputPseudo = findViewById(R.id.input_pseudo)
        inputPin = findViewById(R.id.input_pin)
        inputBgUrl = findViewById(R.id.input_bg_url)
        switchAppBg = findViewById(R.id.switch_app_bg)
        switchTextColor = findViewById(R.id.switch_text_color)
        btnSave = findViewById(R.id.btn_save)
        mainScrollView = findViewById(R.id.main_scrollview)
        radioGroupAlign = findViewById(R.id.radio_group_align)

        val btnReseauSocial = findViewById<ImageView>(R.id.btn_reseau_social)
        btnReseauSocial.setOnClickListener {
            val url = "https://x-event.straw.page"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        }
        val containerSupport = findViewById<LinearLayout>(R.id.container_support)
        containerSupport.setOnClickListener {
            val url = "https://x-event.straw.page"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        }
        loadSavedData()

        switchAppBg.setOnCheckedChangeListener { _, isChecked ->
            applyAppBackground(inputBgUrl.text.toString().trim(), isChecked)
        }

        btnSave.setOnClickListener {
            saveData()
        }
    }

    private fun loadSavedData() {
        val sharedPref = getSharedPreferences("UmaWidgetPrefs", Context.MODE_PRIVATE)
        inputPseudo.setText(sharedPref.getString("pseudo", ""))
        inputPin.setText(sharedPref.getString("pin", ""))

        val savedUrl = sharedPref.getString("bg_url", "")
        val useAppBg = sharedPref.getBoolean("use_app_bg", false)
        val useBlackText = sharedPref.getBoolean("use_black_text", false)

        val savedAlign = sharedPref.getString("bg_align", "CENTER") ?: "CENTER"
        val radioIdToCheck = when(savedAlign) {
            "TOP" -> R.id.radio_top
            "BOTTOM" -> R.id.radio_bottom
            else -> R.id.radio_center
        }
        radioGroupAlign.check(radioIdToCheck)

        inputBgUrl.setText(savedUrl)
        switchAppBg.isChecked = useAppBg
        switchTextColor.isChecked = useBlackText

        applyAppBackground(savedUrl ?: "", useAppBg)
    }

    private fun saveData() {
        val pseudo = inputPseudo.text.toString().trim()
        val pin = inputPin.text.toString().trim()
        val bgUrl = inputBgUrl.text.toString().trim()
        val useAppBg = switchAppBg.isChecked
        val useBlackText = switchTextColor.isChecked

        if (pseudo.isEmpty() || pin.length != 4) {
            Toast.makeText(this, "Username required and 4-digit PIN!", Toast.LENGTH_SHORT).show()
            return
        }

        btnSave.text = "Verifying..."
        btnSave.isEnabled = false

        val userId = "$pseudo-$pin"
        val databaseUrl = "https://uma-widget-default-rtdb.europe-west1.firebasedatabase.app/"
        val dbRef = FirebaseDatabase.getInstance(databaseUrl).getReference("users").child(userId)

        dbRef.get().addOnSuccessListener { snapshot ->
            if (snapshot.exists()) {
                dbRef.child("bg_url").setValue(bgUrl).addOnSuccessListener {
                    saveLocally(pseudo, pin, bgUrl, useAppBg, useBlackText)
                }.addOnFailureListener {
                    resetButton("Write error")
                }
            } else {
                resetButton("Unknown account. Contact administrator.")
            }
        }.addOnFailureListener {
            resetButton("Network connection error")
        }
    }

    private fun saveLocally(pseudo: String, pin: String, bgUrl: String, useAppBg: Boolean, useBlackText: Boolean) {
        val sharedPref = getSharedPreferences("UmaWidgetPrefs", Context.MODE_PRIVATE)
        val editor = sharedPref.edit()

        val alignement = when(radioGroupAlign.checkedRadioButtonId) {
            R.id.radio_top -> "TOP"
            R.id.radio_bottom -> "BOTTOM"
            else -> "CENTER"
        }

        editor.putString("pseudo", pseudo)
        editor.putString("pin", pin)
        editor.putString("bg_url", bgUrl)
        editor.putBoolean("use_app_bg", useAppBg)
        editor.putBoolean("use_black_text", useBlackText)
        editor.putString("bg_align", alignement)
        editor.apply()

        btnSave.text = "SYNC COMPLETE"
        btnSave.isEnabled = true

        btnSave.postDelayed({
            updateButtonUI()
        }, 1000)
        applyAppBackground(bgUrl, useAppBg)

        // Force main widget update
        val intent = Intent(this, UmaAppWidget::class.java)
        intent.action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
        val ids = AppWidgetManager.getInstance(application).getAppWidgetIds(ComponentName(application, UmaAppWidget::class.java))
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        sendBroadcast(intent)

        // Force small widget update
        val intentSmall = Intent(this, UmaAppWidgetSmall::class.java)
        intentSmall.action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
        val idsSmall = AppWidgetManager.getInstance(application).getAppWidgetIds(ComponentName(application, UmaAppWidgetSmall::class.java))
        intentSmall.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, idsSmall)
        sendBroadcast(intentSmall)
    }

    private fun resetButton(errorMsg: String) {
        btnSave.text = "Save & Sync"
        btnSave.isEnabled = true
        Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
    }

    private fun applyAppBackground(url: String, useInApp: Boolean) {
        val bgImageView = findViewById<ImageView>(R.id.app_background_img)

        if (useInApp && url.isNotEmpty() && url.startsWith("http")) {
            bgImageView.visibility = android.view.View.VISIBLE
            mainScrollView.setBackgroundResource(0)

            Glide.with(this)
                .load(url)
                .centerCrop()
                .into(bgImageView)
        } else {
            bgImageView.visibility = android.view.View.GONE
            mainScrollView.setBackgroundResource(R.drawable.background)
        }
    }

    private fun updateButtonUI() {
        val sharedPref = getSharedPreferences("UmaWidgetPrefs", Context.MODE_PRIVATE)
        val existingPseudo = sharedPref.getString("pseudo", null)

        if (existingPseudo != null) {
            btnSave.text = "EDIT WIDGET"
            btnSave.background.setColorFilter(Color.parseColor("#99FFA500"), PorterDuff.Mode.SRC_ATOP)
        } else {
            btnSave.text = "SAVE & SYNC"
            btnSave.background.clearColorFilter()
        }
    }
}