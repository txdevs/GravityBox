/*
 * Copyright (C) 2018 Peter Gregus for GravityBox Project (C3C076@xda)
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

package com.ceco.pie.gravitybox;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;

import com.ceco.pie.gravitybox.ledcontrol.QuietHours;
import com.ceco.pie.gravitybox.managers.BroadcastMediator;
import com.ceco.pie.gravitybox.managers.FrameworkManagers;
import com.ceco.pie.gravitybox.managers.SysUiManagers;
import com.ceco.pie.gravitybox.managers.SysUiBatteryInfoManager.LowBatteryWarningPolicy;

import android.os.BatteryManager;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class ModLowBatteryWarning {
    private static final String TAG = "GB:ModLowBatteryWarning";
    public static final String PACKAGE_NAME = "com.android.systemui";
    private static final String CLASS_POWER_UI = "com.android.systemui.power.PowerUI";
    private static final String CLASS_POWER_WARNINGS = "com.android.systemui.power.PowerNotificationWarnings";
    private static final String CLASS_BATTERY_SERVICE_LED = "com.android.server.BatteryService$Led";
    public static final boolean DEBUG = false;

    public enum ChargingLed { DEFAULT, EMULATED, CONSTANT, DISABLED }

    private static Object mBatteryLed;
    private static boolean mFlashingLedDisabled;
    private static ChargingLed mChargingLed;
    private static Integer mDashChargingSoundIdOrig;

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    private static BroadcastMediator.Receiver mBroadcastReceiver = (context, intent) -> {
        if (intent.getAction().equals(GravityBoxSettings.ACTION_BATTERY_LED_CHANGED)) {
            if (intent.hasExtra(GravityBoxSettings.EXTRA_BLED_FLASHING_DISABLED)) {
                mFlashingLedDisabled = intent.getBooleanExtra(
                        GravityBoxSettings.EXTRA_BLED_FLASHING_DISABLED, false);
            }
            if (intent.hasExtra(GravityBoxSettings.EXTRA_BLED_CHARGING)) {
                mChargingLed = ChargingLed.valueOf(intent.getStringExtra(
                        GravityBoxSettings.EXTRA_BLED_CHARGING));
            }
            updateLightsLocked();
        }
    };

    private static void updateLightsLocked() {
        if (mBatteryLed == null) return;

        try {
            XposedHelpers.callMethod(mBatteryLed, "updateLightsLocked");
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    public static void initAndroid(final XSharedPreferences prefs, final XSharedPreferences qhPrefs, final ClassLoader classLoader) {
        if (DEBUG) log("initAndroid");
        try {
            final Class<?> batteryServiceClass = XposedHelpers.findClass(CLASS_BATTERY_SERVICE_LED, classLoader);

            mFlashingLedDisabled = prefs.getBoolean(GravityBoxSettings.PREF_KEY_FLASHING_LED_DISABLE, false);
            mChargingLed = ChargingLed.valueOf(prefs.getString(GravityBoxSettings.PREF_KEY_CHARGING_LED, "DEFAULT"));

            XposedBridge.hookAllConstructors(batteryServiceClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    mBatteryLed = param.thisObject;
                    FrameworkManagers.BroadcastMediator.subscribe(mBroadcastReceiver,
                            GravityBoxSettings.ACTION_BATTERY_LED_CHANGED);
                }
            });

            XposedHelpers.findAndHookMethod(batteryServiceClass, "updateLightsLocked", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Object batteryLight = XposedHelpers.getObjectField(param.thisObject, "mBatteryLight");
                    Object o = XposedHelpers.getSurroundingThis(param.thisObject);
                    Object batteryProps = XposedHelpers.getObjectField(o, "mHealthInfo");
                    if (DEBUG) {
                        log("BatteryService LED: updateLightsLocked ENTERED");
                        // for debugging purposes - simulate low battery
                        XposedHelpers.setIntField(batteryProps, "batteryLevel", 10);
                    }

                    final int status = XposedHelpers.getIntField(batteryProps, "batteryStatus");
                    if (status == BatteryManager.BATTERY_STATUS_CHARGING) {
                        if (mChargingLed == ChargingLed.DISABLED) {
                            if (DEBUG) log("Disabling charging led");
                            XposedHelpers.callMethod(batteryLight, "turnOff");
                            param.setResult(null);
                        }
                    } else {
                        if (mFlashingLedDisabled) {
                            if (DEBUG) log("Disabling low battery flashing led");
                            XposedHelpers.callMethod(batteryLight, "turnOff");
                            param.setResult(null);
                        }
                    }
                }
            });
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    // SystemUI package

    static void init(final XSharedPreferences prefs, final XSharedPreferences qhPrefs, ClassLoader classLoader) {
        try {
            if (DEBUG) log("init");

            // for debugging purposes - simulate low battery even if it's not
            if (DEBUG) {
                Class<?> classPowerUI = findClass(CLASS_POWER_UI, classLoader);
                findAndHookMethod(classPowerUI, "findBatteryLevelBucket", int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        param.setResult(-1);
                    }
                });
            }

            Class<?> classPowerWarnings = findClass(CLASS_POWER_WARNINGS, classLoader);
            findAndHookMethod(classPowerWarnings, "updateNotification", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (SysUiManagers.BatteryInfoManager == null) return;

                    LowBatteryWarningPolicy policy = SysUiManagers.BatteryInfoManager
                            .getLowBatteryWarningPolicy();
                    if (DEBUG) log("showLowBatteryWarning called; policy=" + policy);
                    switch (policy) {
                        case DEFAULT:
                            return;
                        case NONINTRUSIVE:
                            XposedHelpers.setBooleanField(param.thisObject, "mPlaySound", false);
                            return;
                        case OFF:
                            XposedHelpers.setBooleanField(param.thisObject, "mWarning", false);
                    }
                }
            });
        } catch (Throwable t) { GravityBox.log(TAG, t); }

        // OOS Dash charging sound
        if (Utils.isOxygenOsRom()) {
            final String CLASS_INDICATION_CTRL_CB = "com.android.systemui.statusbar.KeyguardIndicationController.BaseKeyguardCallback";
            try {
                XposedBridge.hookAllMethods(XposedHelpers.findClass(CLASS_INDICATION_CTRL_CB, classLoader),
                        "onRefreshBatteryInfo", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (isDashSoundDisabled(prefs, qhPrefs)) {
                            Object ctrl = XposedHelpers.getSurroundingThis(param.thisObject);
                            mDashChargingSoundIdOrig = XposedHelpers.getIntField(ctrl, "mChargingSoundId");
                            XposedHelpers.setIntField(ctrl, "mChargingSoundId", 0);
                        }
                    }
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (mDashChargingSoundIdOrig != null) {
                            Object ctrl = XposedHelpers.getSurroundingThis(param.thisObject);
                            XposedHelpers.setIntField(ctrl, "mChargingSoundId",
                                    mDashChargingSoundIdOrig);
                            mDashChargingSoundIdOrig = null;
                        }
                    }
                });
            } catch (Throwable t) {
                GravityBox.log(TAG, t);
            }
        }
    }

    private static boolean isDashSoundDisabled(final XSharedPreferences prefs, final XSharedPreferences qhPrefs) {
        prefs.reload();
        if (!prefs.getBoolean(GravityBoxSettings.PREF_KEY_OOS_DASH_SOUND_DISABLE, false)) {
            qhPrefs.reload();
            QuietHours qh = new QuietHours(qhPrefs);
            return qh.isSystemSoundMuted(QuietHours.SystemSound.CHARGER);
        }
        return true;
    }
}
