package com.fredplayer.app;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.os.Build;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

final class AudioOutputRoute {
    private static final int ANDROID_BT_LATENCY_API = 37;
    private static final String MEDIA_QUALITY_SERVICE = "media_quality";
    private static final String BT_LATENCY_PARAMETER = "bt_latency_us";

    private AudioOutputRoute() {
    }

    static boolean isBluetooth(AudioDeviceInfo device) {
        if (device == null) {
            return false;
        }
        int type = device.getType();
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                || type == AudioDeviceInfo.TYPE_HEARING_AID
                || type == AudioDeviceInfo.TYPE_BLE_HEADSET
                || type == AudioDeviceInfo.TYPE_BLE_SPEAKER
                || type == AudioDeviceInfo.TYPE_BLE_BROADCAST;
    }

    static String key(AudioDeviceInfo device) {
        if (!isBluetooth(device)) {
            return "";
        }
        return device.getType() + ":" + label(device);
    }

    static String label(AudioDeviceInfo device) {
        if (device == null) {
            return "Unknown output";
        }
        CharSequence productName = device.getProductName();
        String name = productName == null ? "" : productName.toString().trim();
        if (!name.isEmpty()) {
            return name;
        }
        return isBluetooth(device) ? "Bluetooth audio" : "Device audio";
    }

    static boolean needsManualCalibration(Context context) {
        if (Build.VERSION.SDK_INT < ANDROID_BT_LATENCY_API) {
            return true;
        }
        try {
            Object manager = context.getSystemService(MEDIA_QUALITY_SERVICE);
            if (manager == null) {
                return true;
            }
            Method capabilitiesMethod = manager.getClass().getMethod(
                    "getParameterCapabilities",
                    List.class);
            Object value = capabilitiesMethod.invoke(
                    manager,
                    Collections.singletonList(BT_LATENCY_PARAMETER));
            if (!(value instanceof List) || ((List<?>) value).isEmpty()) {
                return true;
            }
            Object capability = ((List<?>) value).get(0);
            Method supportedMethod = capability.getClass().getMethod("isSupported");
            return !Boolean.TRUE.equals(supportedMethod.invoke(capability));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return true;
        }
    }
}
