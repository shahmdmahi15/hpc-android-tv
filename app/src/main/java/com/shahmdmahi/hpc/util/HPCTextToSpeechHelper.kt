package com.shahmdmahi.hpc.util

import android.content.Context
import android.content.Intent
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
    private var pendingToken: String = ""
    private var pendingPatient: String = ""
    private var pendingRoom: String = ""

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

            // Discover all installed TTS engines on this Android device/TV
            var preferredEngine: String? = null
            try {
                val pm = context.packageManager
                val ttsIntent = Intent("android.intent.action.TTS_SERVICE")
                val resolveInfos = pm.queryIntentServices(ttsIntent, 0)
                val enginePackages = resolveInfos.map { it.serviceInfo.packageName }
                Log.i(TAG, "Available TTS engines on device: $enginePackages")

                preferredEngine = when {
                    enginePackages.contains("com.google.android.tts") -> "com.google.android.tts"
                    enginePackages.contains("com.samsung.SMT") -> "com.samsung.SMT"
                    enginePackages.contains("com.svox.pico") -> "com.svox.pico"
                    enginePackages.isNotEmpty() -> enginePackages.first()
                    else -> null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to query TTS engines: ${e.message}")
            }

            tts = if (preferredEngine != null) {
                Log.i(TAG, "Instantiating TextToSpeech with preferred engine: $preferredEngine")
                TextToSpeech(context, this, preferredEngine)
            } else {
                Log.i(TAG, "Instantiating TextToSpeech with default system engine")
                TextToSpeech(context, this)
            }
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

                // 2. Search available voices / locales (including South Asian en-IN, en-US, en-GB)
                var enFound = false
                try {
                    val voices = engine.voices
                    if (!voices.isNullOrEmpty()) {
                        val preferredVoice = voices.firstOrNull { v ->
                            val lang = v.locale.language.lowercase()
                            val country = v.locale.country.lowercase()
                            lang == "en" && (country == "in" || country == "us" || country == "gb") && !v.isNetworkConnectionRequired
                        } ?: voices.firstOrNull { v ->
                            v.locale.language.equals("en", ignoreCase = true) && !v.isNetworkConnectionRequired
                        } ?: voices.firstOrNull { v ->
                            v.locale.language.equals("en", ignoreCase = true)
                        }

                        if (preferredVoice != null) {
                            engine.voice = preferredVoice
                            englishLocale = preferredVoice.locale
                            enFound = true
                            Log.i(TAG, "Selected installed voice: ${preferredVoice.name} (${preferredVoice.locale})")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "engine.voices not supported on this engine: ${e.message}")
                }

                if (!enFound) {
                    val candidateLocales = listOf(
                        Locale("en", "IN"), // English (India) - highly common on Asian Android devices
                        Locale.US,
                        Locale.UK,
                        Locale("en", "GB"),
                        Locale.ENGLISH,
                        Locale.getDefault()
                    )

                    for (loc in candidateLocales) {
                        val res = engine.isLanguageAvailable(loc)
                        if (res >= TextToSpeech.LANG_AVAILABLE) {
                            try {
                                engine.language = loc
                                englishLocale = loc
                                enFound = true
                                Log.i(TAG, "Selected English locale: $loc (score: $res)")
                                break
                            } catch (_: Exception) {}
                        }
                    }
                }

                if (!enFound) {
                    try {
                        engine.language = Locale.ENGLISH
                        englishLocale = Locale.ENGLISH
                        Log.i(TAG, "Defaulting to Locale.ENGLISH")
                    } catch (_: Exception) {}
                }

                // 3. Check Bengali support
                val bnCandidates = listOf(
                    Locale("bn", "BD"),
                    Locale("bn", "IN"),
                    Locale("bn")
                )
                for (bnLoc in bnCandidates) {
                    val bnRes = engine.isLanguageAvailable(bnLoc)
                    if (bnRes >= TextToSpeech.LANG_AVAILABLE) {
                        isBengaliSupported = true
                        Log.i(TAG, "Bengali TTS supported with locale $bnLoc (score: $bnRes)")
                        break
                    }
                }
                Log.i(TAG, "Bengali TTS support flag: $isBengaliSupported")

                // 4. Utterance listener for sequential bilingual playback
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.d(TAG, "Speech started: $utteranceId")
                    }

                    override fun onDone(utteranceId: String?) {
                        Log.d(TAG, "Speech completed: $utteranceId")
                        if (utteranceId != null && utteranceId.startsWith("HPC_EN_BILINGUAL_")) {
                            val bnText = pendingBengaliText
                            val token = pendingToken
                            val patient = pendingPatient
                            val room = pendingRoom
                            pendingBengaliText = null
                            pendingToken = ""
                            pendingPatient = ""
                            pendingRoom = ""

                            mainHandler.post {
                                if (bnText != null && isBengaliSupported) {
                                    speakBengaliOnly(bnText)
                                } else {
                                    // If OS has no Bengali TTS voice data installed,
                                    // seamlessly play the bundled offline studio Bengali announcement!
                                    Log.i(TAG, "Playing offline studio Bengali announcement after English speech")
                                    offlineAnnouncer.announceDoctorCall(token, patient, room, "bn", includeChime = false)
                                }
                            }
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        Log.e(TAG, "Speech error on utterance: $utteranceId")
                        clearPendingState()
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        Log.e(TAG, "Speech error on utterance: $utteranceId, code: $errorCode")
                        clearPendingState()
                    }
                })

                isInitialized = true
                Log.i(TAG, "Android Native Text-To-Speech initialized successfully")

                // Execute any speech requested while engine was initializing
                mainHandler.post {
                    pendingSpeech?.invoke()
                    pendingSpeech = null
                }
            }
        } else {
            Log.w(TAG, "Android Native Text-To-Speech engine unavailable on this device (status: $status). Will use bundled offline voice pack.")
            isInitialized = false
        }
    }

    private fun clearPendingState() {
        pendingBengaliText = null
        pendingToken = ""
        pendingPatient = ""
        pendingRoom = ""
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
     * Uses System TTS to announce Patient Name + Token + Room, and seamlessly falls back
     * to bundled offline audio files if TTS is not available on the device!
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
        val cleanPatient = patient?.trim() ?: ""
        val cleanRoom = room?.trim() ?: ""
        val cleanMode = mode?.lowercase() ?: "bilingual"

        val dynamicEn = if (!enText.isNullOrBlank()) {
            enText.trim()
        } else {
            val tokenPart = if (cleanToken.isNotEmpty()) "Token $cleanToken. " else ""
            val patientPart = if (cleanPatient.isNotEmpty()) "Patient $cleanPatient. " else ""
            "Attention please. $tokenPart${patientPart}Please proceed to Room $cleanRoom."
        }

        val dynamicBn = if (!bnText.isNullOrBlank()) {
            bnText.trim()
        } else {
            val tokenPart = if (cleanToken.isNotEmpty()) "টোকেন $cleanToken, " else ""
            val patientPart = if (cleanPatient.isNotEmpty()) "রোগী $cleanPatient, " else ""
            "দয়া করে মনোযোগ দিন। $tokenPart${patientPart}রুম নম্বর $cleanRoom-এ আসুন।"
        }

        Log.i(TAG, "speakDoctorCall - Token: '$cleanToken', Patient: '$cleanPatient', Room: '$cleanRoom', Mode: '$cleanMode'")

        // If TTS is currently initializing, queue the speech and wait up to 2.5s
        if (!isInitialized && tts != null) {
            Log.i(TAG, "TTS initializing... Queuing doctor call for patient '$cleanPatient'")
            // Play chime immediately so announcement starts with bell
            offlineAnnouncer.playChime()
            pendingSpeech = {
                speakDoctorCallInternal(cleanToken, cleanPatient, cleanRoom, cleanMode, dynamicEn, dynamicBn)
            }
            mainHandler.postDelayed({
                if (pendingSpeech != null) {
                    Log.w(TAG, "TTS initialization timed out after 2.5s. Falling back to offline voice pack.")
                    pendingSpeech = null
                    offlineAnnouncer.announceDoctorCall(cleanToken, cleanPatient, cleanRoom, cleanMode, includeChime = false)
                }
            }, 2500)
            return true
        }

        return speakDoctorCallInternal(cleanToken, cleanPatient, cleanRoom, cleanMode, dynamicEn, dynamicBn)
    }

    private fun speakDoctorCallInternal(
        token: String,
        patient: String,
        room: String,
        mode: String,
        englishText: String,
        bengaliText: String
    ): Boolean {
        // If system TTS is healthy and available:
        if (isInitialized && tts != null) {
            try {
                requestAudioFocus()
                val engine = tts!!
                val params = buildMediaParams()

                // 1. Play chime bell first
                offlineAnnouncer.playChime()

                // 2. Wait 750ms for chime chord to ring out, then speak
                mainHandler.postDelayed({
                    try {
                        when (mode) {
                            "bn" -> {
                                if (isBengaliSupported && bengaliText.isNotEmpty()) {
                                    engine.language = Locale("bn", "BD")
                                    engine.speak(bengaliText, TextToSpeech.QUEUE_FLUSH, params, "HPC_BN_${System.currentTimeMillis()}")
                                } else {
                                    offlineAnnouncer.announceDoctorCall(token, patient, room, "bn", includeChime = false)
                                }
                            }
                            "bilingual" -> {
                                engine.language = englishLocale
                                pendingBengaliText = bengaliText
                                pendingToken = token
                                pendingPatient = patient
                                pendingRoom = room
                                engine.speak(englishText, TextToSpeech.QUEUE_FLUSH, params, "HPC_EN_BILINGUAL_${System.currentTimeMillis()}")
                            }
                            else -> {
                                engine.language = englishLocale
                                engine.speak(englishText, TextToSpeech.QUEUE_FLUSH, params, "HPC_EN_${System.currentTimeMillis()}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Deferred speech execution failed, falling back to offline audio", e)
                        offlineAnnouncer.announceDoctorCall(token, patient, room, mode, includeChime = false)
                    }
                }, 750)

                return true
            } catch (e: Exception) {
                Log.e(TAG, "System TTS execution failed, falling back to bundled offline audio", e)
            }
        }

        // Guaranteed Fallback: Play bundled studio voice pack (Ding-Dong chime + Patient + Room)
        Log.i(TAG, "Using Bundled Offline Voice Pack for Doctor Call")
        return offlineAnnouncer.announceDoctorCall(token, patient, room, mode, includeChime = true)
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
        return speakDoctorCall("1", "Test Patient", "101", "bilingual", null, null)
    }

    @JavascriptInterface
    fun speak(text: String?, lang: String? = "en-US") {
        speakAnnouncement(text, text, "en")
    }

    @JavascriptInterface
    fun isReady(): Boolean = true

    @JavascriptInterface
    fun isTtsEngineAvailable(): Boolean = isInitialized

    /**
     * Opens Android System Text-to-Speech Settings directly
     */
    @JavascriptInterface
    fun openTtsSettings(): Boolean {
        return try {
            val intent = Intent("com.android.settings.TTS_SETTINGS").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            try {
                val intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                true
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to open TTS settings", e2)
                false
            }
        }
    }

    @JavascriptInterface
    fun stop() {
        try {
            clearPendingState()
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
            clearPendingState()
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
