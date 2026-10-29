package com.rpg1.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.EditText

class MainActivity : Activity(), GameView.Host {
    private lateinit var gameView: GameView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GameSession.init(this)
        gameView = GameView(this, this)
        setContentView(gameView)
    }

    override fun onPause() {
        super.onPause()
        GameSession.autosave()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    override fun askName(current: String, onResult: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(current)
            setSingleLine()
            filters = arrayOf(InputFilter.LengthFilter(12))
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle("Hero name")
            .setView(input)
            .setPositiveButton("OK") { _, _ -> onResult(input.text.toString().trim().ifEmpty { current }) }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { hideSystemBars() }
            .show()
    }

    override fun onBackPressed() {
        if (!gameView.handleBack()) super.onBackPressed()
    }
}
