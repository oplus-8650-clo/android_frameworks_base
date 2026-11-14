/*
 * Copyright (C) 2024-2025 Paranoid Android
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.vibrator.IVibrator;
import android.os.Binder;
import android.os.CombinedVibration;
import android.os.Envelope;
import android.os.ExtPrebaked;
import android.os.HapticParameter;
import android.os.HapticPlayer;
import android.os.IBinder;
import android.os.PatternHe;
import android.os.PatternHeParameter;
import android.os.RemoteException;
import android.os.RichTapVibrationEffect;
import android.os.ServiceManager;
import android.os.VibrationEffect;
import android.util.Slog;

import java.util.Locale;

import vendor.aac.hardware.richtap.vibrator.IRichtapCallback;
import vendor.aac.hardware.richtap.vibrator.IRichtapVibrator;
import vendor.oplus.hardware.oplusvibrator.IOplusVibrator;

/**
 * Service class managing RichTap and LiveTap vibrator functionality.
 * RichTap uses libaacvibrator.so (AAC motor IC, IRichtapVibrator extension),
 * LiveTap uses libsivibrator.so (SinoWealth motor IC, IOplusVibrator extension).
 */
public class RichTapVibratorService {
    public static final String ACTION_CHANGE_MODE = "richtap_change_mode";
    public static final String EXTRA_MODE = "mode";
    private static final String TAG = RichTapVibratorService.class.getSimpleName();
    private static final String VIBRATOR_DESCRIPTOR = IVibrator.DESCRIPTOR + "/default";
    private static final boolean DEBUG = false;
    private final IRichtapCallback mCallback;
    private final boolean mIsLivetapDevice;

    static boolean isEnabled() {
        return RichTapVibrationEffect.isSupported()
                && (!RichTapVibrationEffect.isLivetapDevice()
                        || LiveTapWaveforms.getInstance() != null);
    }

    private volatile IRichtapVibrator mAacVibratorService = null;
    private volatile IOplusVibrator mOplusVibratorService = null;

    @FunctionalInterface
    private interface ServiceRunnable {
        void run() throws RemoteException;
    }

    public RichTapVibratorService() {
        this(null);
    }

    public RichTapVibratorService(@Nullable IRichtapCallback callback) {
        mCallback = callback;
        mIsLivetapDevice = RichTapVibrationEffect.isLivetapDevice();
    }

    private synchronized boolean ensureServiceConnected() {
        if (mOplusVibratorService != null || mAacVibratorService != null) {
            return true;
        }

        String backend = mIsLivetapDevice ? "LiveTap" : "RichTap";
        if (DEBUG) Slog.d(TAG, "Connecting to " + backend + " backend, descriptor: "
                + VIBRATOR_DESCRIPTOR);

        IVibrator vibratorHalService = IVibrator.Stub.asInterface(
                ServiceManager.getService(VIBRATOR_DESCRIPTOR));

        if (vibratorHalService == null) {
            Slog.w(TAG, "Failed to get HAL service for " + backend);
            return false;
        }

        if (DEBUG) {
            try {
                Slog.d(TAG, "Capabilities: " + vibratorHalService.getCapabilities());
            } catch (Exception e) {
                Slog.w(TAG, "Failed to get capabilities", e);
            }
        }

        try {
            IBinder binder = vibratorHalService.asBinder().getExtension();
            if (binder != null) {
                binder = Binder.allowBlocking(binder);
                String descriptor = binder.getInterfaceDescriptor();
                if (IOplusVibrator.DESCRIPTOR.equals(descriptor)
                        || (descriptor == null && mIsLivetapDevice)) {
                    mOplusVibratorService = IOplusVibrator.Stub.asInterface(binder);
                } else {
                    mAacVibratorService = IRichtapVibrator.Stub.asInterface(binder);
                }
                binder.linkToDeath(new RichTapHalDeathRecipient(this), 0);
                Slog.i(TAG, backend + " vibrator service connected with descriptor: "
                        + descriptor);
                return true;
            } else {
                Slog.e(TAG, "Extension binder is null - " + backend
                        + " HAL may not be running");
            }
        } catch (Exception e) {
            Slog.e(TAG, "Failed to get " + backend + " extension", e);
        }
        return false;
    }

    private boolean runOnService(String errorMessage, ServiceRunnable action) {
        if (ensureServiceConnected()) {
            try {
                action.run();
                return true;
            } catch (RemoteException e) {
                Slog.e(TAG, errorMessage, e);
                resetHalServiceProxy();
            } catch (Exception e) {
                Slog.e(TAG, errorMessage, e);
            }
        }
        return false;
    }

    /**
     * Starts the vibrator for specified duration.
     *
     * @param millis The duration in milliseconds
     */
    public void richTapVibratorOn(long millis) {
        runOnService("Failed to execute vibratorOn", () -> {
            if (DEBUG) Slog.d(TAG, "Executing vibratorOn");
            if (mOplusVibratorService != null) {
                mOplusVibratorService.on((int) millis, mCallback);
            } else if (mAacVibratorService != null) {
                mAacVibratorService.on((int) millis, mCallback);
            }
        });
    }

    /**
     * Sets the vibration amplitude.
     *
     * @param amplitude The amplitude value
     */
    public void richTapVibratorSetAmplitude(int amplitude) {
        runOnService("Failed to set amplitude", () -> {
            if (DEBUG) Slog.d(TAG, "Setting amplitude: " + amplitude);
            if (mOplusVibratorService != null) {
                mOplusVibratorService.setAmplitude(amplitude, mCallback);
            } else if (mAacVibratorService != null) {
                mAacVibratorService.setAmplitude(amplitude, mCallback);
            }
        });
    }

    /**
     * Executes a raw pattern HE effect.
     *
     * @param pattern   The pattern array
     * @param amplitude The amplitude value
     * @param freq      The frequency value
     */
    public void richTapVibratorOnRawPattern(@NonNull int[] pattern, int amplitude, int freq) {
        runOnService("Failed to execute raw pattern", () -> {
            if (DEBUG) Slog.d(TAG, "Executing raw pattern with amplitude: " +
                    amplitude + ", freq: " + freq);
            if (mOplusVibratorService != null) {
                mOplusVibratorService.performHe(1, 0, amplitude, freq, pattern, mCallback);
            } else if (mAacVibratorService != null) {
                mAacVibratorService.performHe(1, 0, amplitude, freq, pattern, mCallback);
            }
        });
    }

    /**
     * Executes a pattern HE effect.
     *
     * @param effect The PatternHe vibration effect
     */
    public void richTapVibratorOnPatternHe(VibrationEffect effect) {
        PatternHe patternHe = (PatternHe) effect;
        int[] pattern = patternHe.getPatternInfo();
        int looper = patternHe.getLooper();
        int interval = patternHe.getInterval();
        int amplitude = patternHe.getAmplitude();
        int freq = patternHe.getFreq();

        runOnService("Failed to execute pattern HE effect", () -> {
            if (DEBUG) Slog.d(TAG, "Executing pattern HE effect");
            if (mOplusVibratorService != null) {
                mOplusVibratorService.performHe(looper, interval, amplitude, freq, pattern, mCallback);
            } else if (mAacVibratorService != null) {
                mAacVibratorService.performHe(looper, interval, amplitude, freq, pattern, mCallback);
            }
        });
    }

    /**
     * Executes an envelope vibration effect.
     *
     * @param relativeTime The relative time array
     * @param scaleArr     The scale array
     * @param freqArr      The frequency array
     * @param steepMode    Whether steep mode is enabled
     * @param amplitude    The amplitude value
     */
    public void richTapVibratorOnEnvelope(int[] relativeTime, int[] scaleArr, int[] freqArr,
            boolean steepMode, int amplitude) {
        int[] params = new int[12];
        for (int i = 0; i < relativeTime.length; i++) {
            params[i * 3] = relativeTime[i];
            params[i * 3 + 1] = scaleArr[i];
            params[i * 3 + 2] = freqArr[i];
            if (DEBUG) {
                Slog.d(TAG, String.format(Locale.US, "relativeTime, scale, freq = { %d, %d, %d }",
                        params[i * 3], params[i * 3 + 1], params[i * 3 + 2]));
            }
        }

        richTapVibratorSetAmplitude(amplitude);

        runOnService("Failed to execute envelope effect", () -> {
            if (DEBUG) Slog.d(TAG, "Executing envelope effect");
            if (mOplusVibratorService != null) {
                mOplusVibratorService.performEnvelope(params, steepMode, mCallback);
            } else if (mAacVibratorService != null) {
                mAacVibratorService.performEnvelope(params, steepMode, mCallback);
            }
        });
    }

    /**
     * Stops all vibrations.
     */
    public void richTapVibratorStop() {
        runOnService("Failed to stop vibrator", () -> {
            if (DEBUG) Slog.d(TAG, "Stopping vibrator");
            if (mOplusVibratorService != null) {
                mOplusVibratorService.stop(mCallback);
            } else if (mAacVibratorService != null) {
                mAacVibratorService.stop(mCallback);
            }
        });
    }

    /**
     * Sets haptic parameters.
     *
     * @param data   The parameter data
     * @param length The length of the data
     */
    private void setHapticParam(int[] data, int length) {
        runOnService("Failed to set haptic parameters", () -> {
            if (DEBUG) Slog.d(TAG, "Setting haptic parameters, length: " + length);
            if (mOplusVibratorService != null) {
                mOplusVibratorService.setHapticParam(data, length, mCallback);
            } else if (mAacVibratorService != null) {
                mAacVibratorService.setHapticParam(data, length, mCallback);
            }
        });
    }

    /**
     * Sets the vibration mode.
     *
     * @param mode The vibration mode
     */
    public void richTapSetVibrationMode(int mode) {
        if (DEBUG) Slog.i(TAG, "Setting vibration mode: " + mode);

        // Stop all vibrations first
        richTapVibratorStop();

        int[] param = new int[]{
                HapticParamType.HAPTIC_DRC.getValue(),
                mode
        };
        setHapticParam(param, param.length);
    }

    /**
     * Performs a predefined effect with the specified ID and scale.
     *
     * @param id    The effect ID
     * @param scale The scale value
     * @return The command id returned by the HAL
     */
    public int richTapVibratorPerform(int id, byte scale) {
        if (ensureServiceConnected()) {
            try {
                if (DEBUG) Slog.d(TAG, "Performing predefined effect: " + id);
                if (mOplusVibratorService != null) {
                    int unsignedStrength = Byte.toUnsignedInt(scale);
                    if (unsignedStrength > 100) {
                        unsignedStrength = Math.round((unsignedStrength * 100.0f) / 255.0f);
                    }
                    byte scaledStrength = (byte) Math.max(1, Math.min(100, unsignedStrength));
                    return mOplusVibratorService.perform(id, scaledStrength, mCallback);
                } else if (mAacVibratorService != null) {
                    return mAacVibratorService.perform(id, scale, mCallback);
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to perform effect", e);
                resetHalServiceProxy();
            } catch (Exception e) {
                Slog.e(TAG, "Failed to perform effect", e);
            }
        }
        return 0;
    }

    boolean playLivetapWaveform(LiveTapWaveforms.Waveform waveform, int intensity) {
        return runOnService("Failed to play LiveTap waveform", () -> {
            IOplusVibrator service = mOplusVibratorService;
            if (service == null) {
                throw new UnsupportedOperationException("LiveTap extension unavailable");
            }
            service.linearMotorVibratorOn(waveform.id(), intensity, waveform.rtp());
        });
    }

    void stopLivetapWaveform() {
        runOnService("Failed to stop LiveTap waveform", () -> {
            IOplusVibrator service = mOplusVibratorService;
            if (service != null) {
                service.linearMotorVibratorOff();
            }
        });
    }

    /**
     * Processes RichTap effect parameters.
     *
     * @param combEffect The combined vibration effect
     * @return true if the effect was processed, false otherwise
     */
    public boolean playRichTapParameterEffect(CombinedVibration combEffect) {
        if (!(combEffect instanceof CombinedVibration.Mono)) {
            return false;
        }

        VibrationEffect effect = ((CombinedVibration.Mono) combEffect).getEffect();

        if (effect instanceof PatternHeParameter param) {
            int interval = param.getInterval();
            int amplitude = param.getAmplitude();
            int freq = param.getFreq();

            if (DEBUG) {
                Slog.d(TAG, "Processing PatternHeParameter - interval: " + interval +
                        ", amplitude: " + amplitude + ", freq: " + freq);
            }

            runOnService("Failed to process PatternHeParameter", () -> {
                if (mOplusVibratorService != null) {
                    mOplusVibratorService.performHeParam(interval, amplitude, freq, mCallback);
                } else if (mAacVibratorService != null) {
                    mAacVibratorService.performHeParam(interval, amplitude, freq, mCallback);
                }
            });
            return true;
        } else if (effect instanceof HapticParameter parameter) {
            int[] param = parameter.getParam();
            int length = parameter.getLength();

            if (DEBUG) {
                Slog.d(TAG, "Processing HapticParameter: " + parameter);
            }

            setHapticParam(param, length);
            return true;
        }

        if (DEBUG) Slog.d(TAG, "Not a RichTap effect, no action taken");
        return false;
    }

    /**
     * Checks if the vibration effect is a RichTap effect.
     *
     * @param effect The vibration effect
     * @param reason The vibration reason
     * @return true if it's a RichTap effect, false otherwise
     */
    public boolean checkIfRichTapEffect(VibrationEffect effect, String reason) {
        if (reason != null && reason.equals(HapticPlayer.VIBRATE_REASON)) {
            return false;
        }

        return (effect instanceof PatternHeParameter
                || effect instanceof PatternHe
                || effect instanceof Envelope
                || effect instanceof HapticParameter
                || effect instanceof ExtPrebaked);
    }

    /**
     * Resets the HAL service proxy.
     */
    void resetHalServiceProxy() {
        synchronized (this) {
            mOplusVibratorService = null;
            mAacVibratorService = null;
        }
    }

    /**
     * Haptic parameter types enum.
     */
    public enum HapticParamType {
        HAPTIC_DRC(0x01);

        private final int type;

        HapticParamType(int type) {
            this.type = type;
        }

        public int getValue() {
            return type;
        }
    }

    /**
     * Death recipient for vibrator HAL service.
     */
    private static final class RichTapHalDeathRecipient implements IBinder.DeathRecipient {
        private final RichTapVibratorService mRichTapService;

        RichTapHalDeathRecipient(@NonNull RichTapVibratorService richTapService) {
            mRichTapService = richTapService;
        }

        @Override
        public void binderDied() {
            Slog.w(TAG, "Vibrator HAL died, resetting proxy");
            synchronized (mRichTapService) {
                mRichTapService.resetHalServiceProxy();
            }
        }
    }
}
