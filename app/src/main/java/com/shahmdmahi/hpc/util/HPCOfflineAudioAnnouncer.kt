package com.shahmdmahi.hpc.util

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 100% Offline Audio Announcer for HPC Hospital Queue System.
 *
 * Uses pre-bundled studio voice clips in app/src/main/assets/audio/
 * to deliver crystal-clear bilingual hospital announcements (Chime, Attention please,
 * Token number, Room number) with zero internet dependency and zero dependency on
 * Google Play Services or Android OS Text-To-Speech engines.
 */
class HPCOfflineAudioAnnouncer(private val context: Context) {

    companion object {
        private const val TAG = "HPC_OfflineAudio"
        private const val ASSET_FOLDER = "audio"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    private var currentPlaylist: List<String> = emptyList()
    private var currentTrackIndex: Int = 0
    private var isPlaying: Boolean = false

    private val availableAssets: Set<String> by lazy {
        try {
            context.assets.list(ASSET_FOLDER)?.toSet() ?: emptySet()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list audio assets", e)
            emptySet()
        }
    }

    fun isAvailable(): Boolean {
        return availableAssets.contains("chime.wav")
    }

    /**
     * Plays hospital chime bell immediately
     */
    fun playChime(): Boolean {
        return playSequence(listOf("chime.wav"))
    }

    /**
     * Announce doctor call with high-fidelity bundled voice pack
     */
    fun announceDoctorCall(
        token: String?,
        room: String?,
        mode: String? = "bilingual",
        includeChime: Boolean = false
    ): Boolean {
        return announceDoctorCall(token, null, room, mode, includeChime)
    }

    fun announceDoctorCall(
        token: String?,
        patient: String?,
        room: String?,
        mode: String? = "bilingual",
        includeChime: Boolean = false
    ): Boolean {
        val cleanToken = token?.trim()?.replace(Regex("[^0-9]"), "") ?: ""
        val cleanRoom = room?.trim() ?: ""
        val cleanPatient = patient?.trim() ?: ""
        val announcementMode = mode?.lowercase() ?: "bilingual"

        Log.i(TAG, "Announcing Doctor Call - Token: '$cleanToken', Patient: '$cleanPatient', Room: '$cleanRoom', Mode: '$announcementMode', Chime: $includeChime")

        val playlist = mutableListOf<String>()

        // 1. Resonant Hospital Bell Chime (Ding-Dong) if requested
        if (includeChime) {
            playlist.add("chime.wav")
        }

        // 2. English Announcement
        if (announcementMode == "en" || announcementMode == "bilingual") {
            playlist.add("en_attention_please.mp3")

            if (cleanToken.isNotEmpty()) {
                playlist.add("en_token.mp3")
                addEnglishNumberClips(playlist, cleanToken)
            }

            if (cleanPatient.isNotEmpty() && availableAssets.contains("en_patient.mp3")) {
                playlist.add("en_patient.mp3")
            }

            if (cleanRoom.isNotEmpty()) {
                playlist.add("en_please_proceed_to_room.mp3")
                addEnglishRoomClips(playlist, cleanRoom)
            }
        }

        // 3. Bengali Announcement
        if (announcementMode == "bn" || announcementMode == "bilingual") {
            playlist.add("bn_attention_please.mp3")

            if (cleanToken.isNotEmpty()) {
                playlist.add("bn_token.mp3")
                addBengaliNumberClips(playlist, cleanToken)
            }

            if (cleanPatient.isNotEmpty() && availableAssets.contains("bn_rogi.mp3")) {
                playlist.add("bn_rogi.mp3")
            }

            if (cleanRoom.isNotEmpty()) {
                playlist.add("bn_room.mp3")
                addBengaliRoomClips(playlist, cleanRoom)
                playlist.add("bn_ashun.mp3")
            }
        }

        return playSequence(playlist)
    }

    private fun addEnglishNumberClips(playlist: MutableList<String>, numberStr: String) {
        val n = numberStr.toIntOrNull()
        if (n != null && n in 1..100) {
            val file = "en_$n.mp3"
            if (availableAssets.contains(file)) {
                playlist.add(file)
                return
            }
        }

        // Fallback: pronounce digit by digit
        for (ch in numberStr) {
            if (ch.isDigit()) {
                val digitFile = "en_d$ch.mp3"
                if (availableAssets.contains(digitFile)) {
                    playlist.add(digitFile)
                }
            }
        }
    }

    private fun addEnglishRoomClips(playlist: MutableList<String>, roomStr: String) {
        val clean = roomStr.trim()
        val n = clean.toIntOrNull()
        if (n != null && n in 1..100) {
            val file = "en_$n.mp3"
            if (availableAssets.contains(file)) {
                playlist.add(file)
                return
            }
        }

        if (clean.length == 1 && clean[0].isLetter()) {
            val letterFile = "en_${clean.lowercase()}.mp3"
            if (availableAssets.contains(letterFile)) {
                playlist.add(letterFile)
                return
            }
        }

        // Mixed or multi-digit room e.g. "101", "2B"
        for (ch in clean) {
            if (ch.isDigit()) {
                val digitFile = "en_d$ch.mp3"
                if (availableAssets.contains(digitFile)) {
                    playlist.add(digitFile)
                }
            } else if (ch.isLetter()) {
                val letterFile = "en_${ch.lowercaseChar()}.mp3"
                if (availableAssets.contains(letterFile)) {
                    playlist.add(letterFile)
                }
            }
        }
    }

    private fun addBengaliNumberClips(playlist: MutableList<String>, numberStr: String) {
        val n = numberStr.toIntOrNull()
        if (n != null && n in 1..100) {
            val file = "bn_$n.mp3"
            if (availableAssets.contains(file)) {
                playlist.add(file)
                return
            }
        }

        // Fallback: pronounce digit by digit
        for (ch in numberStr) {
            if (ch.isDigit()) {
                val digitFile = "bn_d$ch.mp3"
                if (availableAssets.contains(digitFile)) {
                    playlist.add(digitFile)
                }
            }
        }
    }

    private fun addBengaliRoomClips(playlist: MutableList<String>, roomStr: String) {
        val clean = roomStr.trim()
        val n = clean.toIntOrNull()
        if (n != null && n in 1..100) {
            val file = "bn_$n.mp3"
            if (availableAssets.contains(file)) {
                playlist.add(file)
                return
            }
        }

        for (ch in clean) {
            if (ch.isDigit()) {
                val digitFile = "bn_d$ch.mp3"
                if (availableAssets.contains(digitFile)) {
                    playlist.add(digitFile)
                }
            } else if (ch.isLetter()) {
                val letterFile = "en_${ch.lowercaseChar()}.mp3"
                if (availableAssets.contains(letterFile)) {
                    playlist.add(letterFile)
                }
            }
        }
    }

    @Synchronized
    private fun playSequence(playlist: List<String>): Boolean {
        if (playlist.isEmpty()) return false

        stop()
        requestAudioFocusAndUnmute()

        currentPlaylist = playlist
        currentTrackIndex = 0
        isPlaying = true

        mainHandler.post {
            playNextTrack()
        }
        return true
    }

    private fun playNextTrack() {
        if (!isPlaying) return

        if (currentTrackIndex >= currentPlaylist.size) {
            stop()
            return
        }

        val filename = currentPlaylist[currentTrackIndex++]
        val assetPath = "$ASSET_FOLDER/$filename"

        try {
            val afd: AssetFileDescriptor = context.assets.openFd(assetPath)
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setVolume(1.0f, 1.0f)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()

                setOnCompletionListener {
                    it.release()
                    mediaPlayer = null
                    mainHandler.post { playNextTrack() }
                }

                setOnErrorListener { it, what, extra ->
                    Log.w(TAG, "MediaPlayer error on $assetPath (what=$what, extra=$extra)")
                    it.release()
                    mediaPlayer = null
                    mainHandler.post { playNextTrack() }
                    true
                }

                prepare()
                start()
            }
            mediaPlayer = mp
        } catch (e: Exception) {
            Log.e(TAG, "Error playing audio asset: $assetPath", e)
            mainHandler.post { playNextTrack() }
        }
    }

    @Synchronized
    fun stop() {
        isPlaying = false
        currentPlaylist = emptyList()
        currentTrackIndex = 0
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {}
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
    }

    private fun requestAudioFocusAndUnmute() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return

            // Ensure STREAM_MUSIC is loud and unmuted for TV / Kiosk speakers
            val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (currentVol == 0 && maxVol > 0) {
                val targetVol = (maxVol * 0.85).toInt().coerceAtLeast(1)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                Log.i(TAG, "Unmuted STREAM_MUSIC to $targetVol/$maxVol")
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
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request audio focus: ${e.message}")
        }
    }
}
