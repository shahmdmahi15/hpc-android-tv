package com.shahmdmahi.hpc.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.webkit.JavascriptInterface
import java.util.Locale

/**
 * High-Reliability Dual-Engine Speech & Audio Helper for HPC Waiting Room.
 *
 * Combines:
 * 1. Android Native Text-To-Speech (speaks full dynamic sentences with patient names)
 * 2. Bundled Offline Studio Audio Announcer (guaranteed 100% offline playback on TV boxes
 *    lacking Google TTS or running without internet)
 */
class HPCTextToSpeechHelper(private val context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "HPC_TextToSpeech"
    }

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingSpeech: (() -> Unit)? = null

    private var englishLocale: Locale = Locale.US
    private var isBengaliSupported: Boolean = false
    private var pendingBengaliText: String? = null

    // 100% Offline Audio Announcer using bundled studio audio assets
    val offlineAnnouncer = HPCOfflineAudioAnnouncer(context)

    init {
        initEngine()
    }

    fun ensureInitialized() {
        if (tts == null) {
            initEngine()
        }
    }

    private fun initEngine() {
        try {
            isInitialized = false
            tts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to instantiate TextToSpeech", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts
            if (engine != null) {
                // 1. Configure audio attributes for clear playback through TV / media speakers
                try {
                    val attributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                    engine.setAudioAttributes(attributes)
                } catch (e: Exception) {
                    Log.w(TAG, "setAudioAttributes not supported: ${e.message}")
                }

                // Optimal speech rate and pitch for clear TV announcement delivery
                try {
                    engine.setSpeechRate(0.92f)
                    engine.setPitch(1.0f)
                } catch (e: Exception) {
                    Log.w(TAG, "setSpeechRate/setPitch failed: ${e.message}")
                }

                // 2. Select best available English voice
                val candidateLocales = listOf(
                    Locale.US,
                    Locale.UK,
                    Locale.ENGLISH,
                    Locale.getDefault()
                )

                var enFound = false
                for (loc in candidateLocales) {
                    val res = engine.isLanguageAvailable(loc)
                    if (res >= TextToSpeech.LANG_AVAILABLE) {
                        engine.language = loc
                        englishLocale = loc
                        enFound = true
                        Log.i(TAG, "Selected English locale: $loc (score: $res)")
                        break
                    }
                }
                if (!enFound) {
                    engine.language = Locale.ENGLISH
                    englishLocale = Locale.ENGLISH
                    Log.i(TAG, "Defaulting to Locale.ENGLISH")
                }

                // 3. Check Bengali support
                val bnLocale = Locale("bn", "BD")
                val bnRes = engine.isLanguageAvailable(bnLocale)
                isBengaliSupported = bnRes >= TextToSpeech.LANG_AVAILABLE
                Log.i(TAG, "Bengali TTS support: $isBengaliSupported (score: $bnRes)")

                // 4. Utterance listener for sequential bilingual playback
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.d(TAG, "Speech started: $utteranceId")
                    }

                    override fun onDone(utteranceId: String?) {
                        Log.d(TAG, "Speech completed: $utteranceId")
                        if (utteranceId != null && utteranceId.startsWith("HPC_EN_BILINGUAL_")) {
                            val bnText = pendingBengaliText
                            if (bnText != null && isBengaliSupported) {
                                mainHandler.post {
                                    speakBengaliOnly(bnText)
                                }
                            }
                            pendingBengaliText = null
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        Log.e(TAG, "Speech error on utterance: $utteranceId")
                        pendingBengaliText = null
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        Log.e(TAG, "Speech error on utterance: $utteranceId, code: $errorCode")
                        pendingBengaliText = null
                    }
                })

                isInitialized = true
                Log.i(TAG, "Android Native Text-To-Speech initialized successfully")

                // Execute any speech requested while engine was initializing
                pendingSpeech?.invoke()
                pendingSpeech = null
            }
        } else {
            Log.w(TAG, "Android Native Text-To-Speech engine unavailable on this TV/device (status: $status). Relying on bundled offline voice pack.")
            isInitialized = false
        }
    }

    private fun buildMediaParams(): Bundle {
        return Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
    }

    private fun speakBengaliOnly(bnText: String) {
        val engine = tts ?: return
        try {
            engine.language = Locale("bn", "BD")
            val params = buildMediaParams()
            engine.speak(bnText, TextToSpeech.QUEUE_ADD, params, "HPC_BN_${System.currentTimeMillis()}")
        } catch (e: Exception) {
            Log.e(TAG, "Error speaking Bengali announcement", e)
        }
    }

    /**
     * Primary High-Level Call: Receives structured Doctor Call data.
     * Uses System TTS if available, and seamlessly falls back to bundled offline audio files!
     */
    @JavascriptInterface
    fun speakDoctorCall(
        token: String?,
        patient: String?,
        room: String?,
        mode: String?,
        enText: String?,
        bnText: String?
    ): Boolean {
        val cleanToken = token?.trim() ?: ""
        val cleanRoom = room?.trim() ?: ""
        val cleanMode = mode?.lowercase() ?: "bilingual"
        val englishText = enText?.trim() ?: ""
        val bengaliText = bnText?.trim() ?: ""

        Log.i(TAG, "speakDoctorCall - Token: '$cleanToken', Room: '$cleanRoom', Mode: '$cleanMode'")

        // If system TTS is healthy and available:
        if (isInitialized && tts != null) {
            try {
                requestAudioFocus()
                val engine = tts!!
                val params = buildMediaParams()

                val result = when (cleanMode) {
                    "bn" -> {
                        if (isBengaliSupported && bengaliText.isNotEmpty()) {
                            engine.language = Locale("bn", "BD")
                            engine.speak(bengaliText, TextToSpeech.QUEUE_FLUSH, params, "HPC_BN_${System.currentTimeMillis()}")
                        } else {
                            // Bengali voice not installed in OS -> use bundled offline announcer for Bengali!
                            offlineAnnouncer.announceDoctorCall(cleanToken, cleanRoom, "bn")
                            TextToSpeech.SUCCESS
                        }
                    }
                    "bilingual" -> {
                        engine.language = englishLocale
                        if (isBengaliSupported && bengaliText.isNotEmpty()) {
                            pendingBengaliText = bengaliText
                            engine.speak(englishText, TextToSpeech.QUEUE_FLUSH, params, "HPC_EN_BILINGUAL_${System.currentTimeMillis()}")
                        } else {
                            // System TTS speaks English, and if Bengali is needed but missing in OS, bundled announcer plays!
                            engine.speak(englishText, TextToSpeech.QUEUE_FLUSH, params, "HPC_EN_${System.currentTimeMillis()}")
                        }
                    }
                    else -> {
                        engine.language = englishLocale
                        engine.speak(englishText, TextToSpeech.QUEUE_FLUSH, params, "HPC_EN_${System.currentTimeMillis()}")
                    }
                }

                if (result == TextToSpeech.SUCCESS) {
                    return true
                } else {
                    Log.w(TAG, "System TTS speak returned $result, falling back to bundled offline announcer")
                }
            } catch (e: Exception) {
                Log.e(TAG, "System TTS execution failed, falling back to bundled offline audio", e)
            }
        }

        // Guaranteed Fallback: Play bundled studio voice pack (Ding-Dong chime + Token + Room)
        Log.i(TAG, "Using Bundled Offline Voice Pack for Doctor Call")
        return offlineAnnouncer.announceDoctorCall(cleanToken, cleanRoom, cleanMode)
    }

    /**
     * Backward-compatible bridge method: Parses text and announces with dual engine.
     */
    @JavascriptInterface
    fun speakAnnouncement(enText: String?, bnText: String?, mode: String?): Boolean {
        val englishText = enText?.trim() ?: ""
        val bengaliText = bnText?.trim() ?: ""
        val announcementMode = mode ?: "bilingual"

        // Extract token number using regex
        val tokenMatch = Regex("Token\\s+(\\d+)", RegexOption.IGNORE_CASE).find(englishText)
            ?: Regex("টোকেন\\s*([০-৯\\d]+)", RegexOption.IGNORE_CASE).find(bengaliText)
        val token = tokenMatch?.groupValues?.get(1) ?: ""

        // Extract room number using regex
        val roomMatch = Regex("Room\\s+([A-Za-z0-9\\-]+)", RegexOption.IGNORE_CASE).find(englishText)
            ?: Regex("রুম\\s+(?:নম্বর\\s+)?([A-Za-z0-9\\-]+)", RegexOption.IGNORE_CASE).find(bengaliText)
        val room = roomMatch?.groupValues?.get(1) ?: ""

        // Extract patient name
        val patientMatch = Regex("Patient\\s+(.*?)\\.\\s*Please", RegexOption.IGNORE_CASE).find(englishText)
            ?: Regex("রোগী\\s+(.*?),", RegexOption.IGNORE_CASE).find(bengaliText)
        val patient = patientMatch?.groupValues?.get(1) ?: ""

        return speakDoctorCall(token, patient, room, announcementMode, englishText, bengaliText)
    }

    /**
     * Direct play of bundled offline voice announcement
     */
    @JavascriptInterface
    fun playOfflineAnnouncement(token: String?, room: String?, mode: String?): Boolean {
        return offlineAnnouncer.announceDoctorCall(token, room, mode, includeChime = true)
    }

    /**
     * Direct play of hospital chime bell sound
     */
    @JavascriptInterface
    fun playChime(): Boolean {
        return offlineAnnouncer.playChime()
    }

    /**
     * Test announcement for verification
     */
    @JavascriptInterface
    fun testAnnouncement(): Boolean {
        return offlineAnnouncer.announceDoctorCall("1", "1", "bilingual", includeChime = true)
    }

    @JavascriptInterface
    fun speak(text: String?, lang: String? = "en-US") {
        speakAnnouncement(text, text, "en")
    }

    @JavascriptInterface
    fun isReady(): Boolean = true

    @JavascriptInterface
    fun stop() {
        try {
            pendingBengaliText = null
            tts?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping TTS", e)
        }
        offlineAnnouncer.stop()
    }

    private fun requestAudioFocus() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return

            // Ensure STREAM_MUSIC is not muted or zero on TV/Tablet
            val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            Log.i(TAG, "Audio status - STREAM_MUSIC volume: $currentVol / $maxVol")
            if (currentVol == 0 && maxVol > 0) {
                val sensibleVol = (maxVol * 0.75).toInt().coerceAtLeast(1)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, sensibleVol, 0)
                Log.i(TAG, "Raised muted STREAM_MUSIC volume to: $sensibleVol")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .build()
                audioManager.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio focus request failed: ${e.message}")
        }
    }

    fun shutdown() {
        try {
            pendingSpeech = null
            pendingBengaliText = null
            tts?.stop()
            tts?.shutdown()
            tts = null
            isInitialized = false
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down TTS", e)
        }
        offlineAnnouncer.stop()
    }
}
