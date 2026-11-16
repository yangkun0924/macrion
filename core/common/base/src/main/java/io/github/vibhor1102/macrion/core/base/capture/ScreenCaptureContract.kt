/* Copyright (C) 2026 Macrion contributors. Licensed under the GNU GPL v3 or later. */
package io.github.vibhor1102.macrion.core.base.capture

/**
 * Contract allowing to start a smart scenario without any screen capture.
 *
 * A scenario without screen events (only trigger events, with actions such as intents, element taps, pauses...)
 * does not need the screen content. Passing [RESULT_CODE_NO_SCREEN_CAPTURE] as the media projection result code
 * starts the detector without a media projection, so no system "start recording" consent is shown. This is what makes
 * one tap launch from a home screen shortcut possible.
 */
object ScreenCaptureContract {
    /** Fake media projection result code meaning "do not capture the screen". Never returned by Android. */
    const val RESULT_CODE_NO_SCREEN_CAPTURE: Int = 0x4E4F4341
}
