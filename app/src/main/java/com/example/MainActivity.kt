package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.example.ime.settings.SettingsActivity

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    startActivity(Intent(this, SettingsActivity::class.java))
    finish()
  }
}
