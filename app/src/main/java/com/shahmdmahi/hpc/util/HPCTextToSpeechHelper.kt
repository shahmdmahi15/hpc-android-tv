package com.shahmdmahi.hpc.util

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import android.webkit.JavascriptInterface
import java.util.Locale

class HPCTextToSpeechHelper(context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "HPC_TextToSpeech"
    }

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isInitialized = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Default language US missing/unsupported on this TTS engine")
            } else {
                isInitialized = true
                Log.i(TAG, "Offline Android Native Text-To-Speech initialized successfully")
            }
        } else {
            Log.e(TAG, "Failed to initialize Android Native Text-To-Speech engine (status: $status)")
        }
    }

    @JavascriptInterface
    fun speakAnnouncement(enText: String?, bnText: String?, mode: String?) {
        if (!isInitialized || tts == null) {
            Log.w(TAG, "TTS engine not ready yet for announcement")
            return
        }

        try {
            val englishText = enText ?: ""
            val bengaliText = bnText ?: ""
            val announcementMode = mode ?: "en"

            Log.i(TAG, "Announcement request - Mode: $announcementMode, EN: '$englishText', BN: '$bengaliText'")

            when (announcementMode) {
                "en" -> {
                    tts?.language = Locale.US
                    tts?.speak(englishText, TextToSpeech.QUEUE_FLUSH, null, "HPC_EN_${System.currentTimeMillis()}")
                }
                "bn" -> {
                    tts?.language = Locale("bn", "BD")
                    tts?.speak(bengaliText, TextToSpeech.QUEUE_FLUSH, null, "HPC_BN_${System.currentTimeMillis()}")
                }
                "bilingual" -> {
                    tts?.language = Locale.US
                    tts?.speak(englishText, TextToSpeech.QUEUE_FLUSH, null, "HPC_EN_${System.currentTimeMillis()}")
                    tts?.language = Locale("bn", "BD")
                    tts?.speak(bengaliText, TextToSpeech.QUEUE_ADD, null, "HPC_BN_${System.currentTimeMillis()}")
                }
                else -> {
                    tts?.language = Locale.US
                    tts?.speak(englishText, TextToSpeech.QUEUE_FLUSH, null, "HPC_DEF_${System.currentTimeMillis()}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing speakAnnouncement", e)
        }
    }

    @JavascriptInterface
    fun speak(text: String?, lang: String? = "en-US") {
        speakAnnouncement(text ?: "", text ?: "", "en")
    }

    @JavascriptInterface
    fun stop() {
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping TTS", e)
        }
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
            isInitialized = false
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down TTS", e)
        }
    }
}
