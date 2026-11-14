/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.vibrator;

import android.annotation.Nullable;
import android.hardware.vibrator.CompositePrimitive;
import android.os.Environment;
import android.os.HapticPlayer;
import android.os.RichTapVibrationEffect;
import android.os.VibrationEffect;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;

import com.android.internal.util.XmlUtils;
import com.android.modules.utils.TypedXmlPullParser;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;

/** Resolves the standard LiveTap effects against the device's ODM waveform map. */
final class LiveTapWaveforms {
    private static final String TAG = "LiveTapWaveforms";

    // Stock effect IDs. The device XML supplies their waveform indices and durations.
    static final int CLICK = 0;
    static final int HEAVY_CLICK = 1;
    static final int THUD = 2;
    static final int DOUBLE_CLICK = 3;
    static final int POP = 49;
    static final int TICK = 68;
    static final int STRONG_GRANULAR = 69;
    // Internal keys for the keyboard roles named by the device XML.
    static final int KEYBOARD_LIGHT = -2;
    static final int KEYBOARD_MEDIUM = -3;
    static final int KEYBOARD_STRONG = -4;
    private static final int PRIMITIVE_CLICK = -5;
    private static final int PRIMITIVE_TICK = -6;
    static final int MAX_SHORT_DURATION_MS = 102;

    record Waveform(int id, int durationMs, boolean rtp) {}

    record Chirp(int durationMs, int[] pattern) {}

    private static final Chirp SLOW_RISE = chirp(500,
            0, 0, -45, 140, 20, -32, 475, 100, 0, 500, 0, 0);
    private static final Chirp QUICK_RISE = chirp(150,
            0, 0, -45, 40, 28, -30, 137, 100, 0, 150, 0, 0);
    private static final Chirp QUICK_FALL = chirp(100,
            0, 0, 40, 12, 100, 30, 70, 35, 7, 100, 0, 0);
    private static final Chirp SPIN = chirp(150,
            0, 0, -40, 32, 35, -24, 75, 85, 0, 115, 35, -24, 150, 0, -40);

    private static final class Config {
        private static final LiveTapWaveforms INSTANCE = load(
                new File(Environment.getOdmDirectory(), "etc/vibrator"));
    }

    @Nullable
    static LiveTapWaveforms getInstance() {
        return RichTapVibrationEffect.isLivetapDevice() ? Config.INSTANCE : null;
    }

    @Nullable
    static LiveTapWaveforms load(File directory) {
        LiveTapWaveforms config = new LiveTapWaveforms(directory);
        return config.mWaveforms.size() == 0 ? null : config;
    }

    private final SparseArray<Waveform> mWaveforms = new SparseArray<>();

    private LiveTapWaveforms(File directory) {
        read(new File(directory, "effect2waveform.xml"));
        read(new File(directory, "effect_waveform.xml"));
        read(new File(directory, "effect_waveform_override.xml"));
    }

    @Nullable
    Waveform get(int effectId) {
        return mWaveforms.get(effectId);
    }

    static int fromPrebaked(int effectId) {
        return switch (effectId) {
            case VibrationEffect.EFFECT_CLICK -> CLICK;
            case VibrationEffect.EFFECT_DOUBLE_CLICK -> DOUBLE_CLICK;
            case VibrationEffect.EFFECT_TICK, VibrationEffect.EFFECT_TEXTURE_TICK -> TICK;
            case VibrationEffect.EFFECT_HEAVY_CLICK -> HEAVY_CLICK;
            case VibrationEffect.EFFECT_THUD -> THUD;
            case VibrationEffect.EFFECT_POP -> POP;
            default -> -1;
        };
    }

    @Nullable
    Waveform getPrimitiveWaveform(int primitiveId) {
        return switch (primitiveId) {
            case CompositePrimitive.CLICK -> {
                Waveform click = get(PRIMITIVE_CLICK);
                yield click != null ? click : get(CLICK);
            }
            case CompositePrimitive.LIGHT_TICK -> get(PRIMITIVE_TICK);
            case CompositePrimitive.LOW_TICK -> get(TICK);
            case CompositePrimitive.THUD -> get(THUD);
            default -> null;
        };
    }

    @Nullable
    static Chirp getPrimitiveChirp(int primitiveId) {
        return switch (primitiveId) {
            case CompositePrimitive.SLOW_RISE -> SLOW_RISE;
            case CompositePrimitive.QUICK_RISE -> QUICK_RISE;
            case CompositePrimitive.QUICK_FALL -> QUICK_FALL;
            case CompositePrimitive.SPIN -> SPIN;
            default -> null;
        };
    }

    private static Chirp chirp(int durationMs, int... points) {
        // HE 1.0 continuous event with time, relative intensity and frequency offset per point.
        int[] pattern = new int[HapticPlayer.HE_VALUE_LENGTH + 1];
        pattern[0] = 3; // HE 1.0 with up to 16 points.
        pattern[1 + HapticPlayer.HE_TYPE] = HapticPlayer.CONTINUOUS_EVENT;
        pattern[1 + HapticPlayer.HE_INTENSITY] = 100;
        pattern[1 + HapticPlayer.HE_FREQUENCY] = 50;
        pattern[1 + HapticPlayer.HE_DURATION] = durationMs;
        pattern[1 + HapticPlayer.HE_POINT_COUNT] = points.length / 3;
        System.arraycopy(points, 0, pattern, 1 + HapticPlayer.HE_CURVE_POINT_0_TIME, points.length);
        return new Chirp(durationMs, pattern);
    }

    static int fromDuration(long milliseconds, boolean keyboard) {
        if (milliseconds <= 12) {
            return keyboard ? KEYBOARD_LIGHT : TICK;
        }
        if (milliseconds <= 52) {
            return keyboard ? KEYBOARD_MEDIUM : HEAVY_CLICK;
        }
        return keyboard ? KEYBOARD_STRONG : STRONG_GRANULAR;
    }

    static int motorLevel(float amplitude) {
        // The driver looks up VMAX and gain by exact 100-point levels.
        return Math.max(8, Math.min(24, Math.round(amplitude * 24))) * 100;
    }

    static int primitiveMotorLevel(int primitiveId, float scale) {
        if (primitiveId == CompositePrimitive.LOW_TICK) {
            // Use the full calibrated gain range for low-amplitude ticks.
            return Math.round(8 + 16 * scale) * 100;
        }
        return motorLevel(scale);
    }

    private void read(File file) {
        try (InputStream stream = Files.newInputStream(file.toPath())) {
            TypedXmlPullParser parser = Xml.resolvePullParser(stream);
            XmlUtils.beginDocument(parser, "vibration_effect_map");
            SparseArray<Waveform> entries = new SparseArray<>();
            int rootDepth = parser.getDepth();
            while (XmlUtils.nextElementWithin(parser, rootDepth)) {
                if ("effect".equals(parser.getName())) {
                    int key = getEffectKey(parser);
                    int id = parser.getAttributeInt(null, "waveform_id", -1);
                    int duration = parser.getAttributeInt(null, "duration", -1);
                    String type = parser.getAttributeValue(null, "type");
                    if (key != -1 && id > 0 && duration > 0
                            && ("RAM".equals(type) || "RTP".equals(type))) {
                        entries.put(key, new Waveform(id, duration, "RTP".equals(type)));
                    }
                }
            }
            for (int i = 0; i < entries.size(); i++) {
                mWaveforms.put(entries.keyAt(i), entries.valueAt(i));
            }
        } catch (NoSuchFileException ignored) {
            // Each XML file is optional.
        } catch (IOException | XmlPullParserException e) {
            Slog.w(TAG, "Could not read " + file, e);
        }
    }

    private static int getEffectKey(TypedXmlPullParser parser) {
        String description = parser.getAttributeValue(null, "desc");
        int effectId = parser.getAttributeInt(null, "effect_id", -1);
        return switch (description) {
            case "effect_other_keyboard_weak" -> KEYBOARD_LIGHT;
            case "effect_other_keyboard_medium" -> KEYBOARD_MEDIUM;
            case "effect_other_keyboard_strong" -> KEYBOARD_STRONG;
            case "effect_razer_clicky_press" -> PRIMITIVE_CLICK;
            case "effect_razer_linear_press" -> PRIMITIVE_TICK;
            case null, default -> switch (effectId) {
                case CLICK, HEAVY_CLICK, THUD, DOUBLE_CLICK, POP, TICK, STRONG_GRANULAR -> effectId;
                default -> -1;
            };
        };
    }
}
