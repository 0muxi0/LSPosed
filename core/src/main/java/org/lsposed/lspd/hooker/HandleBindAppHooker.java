/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LSPosed is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSPosed.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2020 EdXposed Contributors
 * Copyright (C) 2021 LSPosed Contributors
 */

package org.lsposed.lspd.hooker;

import static org.lsposed.lspd.config.LSPApplicationServiceClient.serviceClient;

import android.annotation.SuppressLint;
import android.app.ActivityThread;
import android.app.ContextImpl;
import android.app.LoadedApk;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.CompatibilityInfo;
import android.content.res.XResources;
import android.os.IBinder;

import org.lsposed.lspd.util.Hookers;
import org.lsposed.lspd.util.MetaDataReader;
import org.lsposed.lspd.util.Utils;

import java.io.File;
import java.io.IOException;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XposedInit;

// normal process initialization (for new Activity, Service, BroadcastReceiver etc.)
public class HandleBindAppHooker extends XC_MethodHook {
    String appDataDir;

    public HandleBindAppHooker(String appDataDir) {
        this.appDataDir = appDataDir;
    }

    @Override
    protected void beforeHookedMethod(MethodHookParam param) {
        try {
            Hookers.logD("ActivityThread#handleBindApplication() starts");
            ActivityThread activityThread = (ActivityThread) param.thisObject;
            Object bindData = param.args[0];
            final ApplicationInfo appInfo = (ApplicationInfo) XposedHelpers.getObjectField(bindData, "appInfo");
            // save app process name here for later use
            String appProcessName = (String) XposedHelpers.getObjectField(bindData, "processName");
            String reportedPackageName = appInfo.packageName.equals("android") ? "system" : appInfo.packageName;
            Utils.logD("processName=" + appProcessName +
                    ", packageName=" + reportedPackageName + ", appDataDir=" + appDataDir);

            CompatibilityInfo compatInfo = (CompatibilityInfo) XposedHelpers.getObjectField(bindData, "compatInfo");
            if (appInfo.sourceDir == null) {
                return;
            }
            XposedHelpers.setObjectField(activityThread, "mBoundApplication", bindData);
            XposedInit.loadedPackagesInProcess.add(reportedPackageName);
            LoadedApk loadedApk = activityThread.getPackageInfoNoCheck(appInfo, compatInfo);

            XResources.setPackageNameForResDir(appInfo.packageName, loadedApk.getResDir());

            String processName = (String) XposedHelpers.getObjectField(bindData, "processName");


            IBinder moduleBinder = serviceClient.requestModuleBinder(appInfo.packageName);
            boolean isModule = moduleBinder != null;
            int xposedminversion = -1;
            boolean xposedsharedprefs = false;
            try {
                if (isModule) {
                    Map<String, Object> metaData = MetaDataReader.getMetaData(new File(appInfo.sourceDir));
                    Object minVersionRaw = metaData.get("xposedminversion");
                    if (minVersionRaw instanceof Integer) {
                        xposedminversion = (Integer) minVersionRaw;
                    } else if (minVersionRaw instanceof String) {
                        xposedminversion = MetaDataReader.extractIntPart((String) minVersionRaw);
                    }
                    xposedsharedprefs = metaData.containsKey("xposedsharedprefs");
                }
            } catch (NumberFormatException | IOException e) {
                Hookers.logE("ApkParser fails", e);
            }

            if (isModule && (xposedminversion > 92 || xposedsharedprefs)) {
                Utils.logW("New modules detected, hook preferences");
                XposedHelpers.findAndHookMethod(ContextImpl.class, "checkMode", int.class, new XC_MethodHook() {
                    @SuppressWarnings("deprecation")
                    @SuppressLint("WorldReadableFiles")
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (((int) param.args[0] & Context.MODE_WORLD_READABLE) != 0) {
                            param.setThrowable(null);
                        }
                    }
                });
                XposedHelpers.findAndHookMethod(ContextImpl.class, "getPreferencesDir", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        param.setResult(new File(serviceClient.getPrefsPath(appInfo.packageName)));
                    }
                });
            }
            LoadedApkGetCLHooker hook = new LoadedApkGetCLHooker(loadedApk, reportedPackageName,
                    processName, true);
            hook.setUnhook(XposedHelpers.findAndHookMethod(
                    LoadedApk.class, "getClassLoader", hook));

        } catch (Throwable t) {
            Hookers.logE("error when hooking bindApp", t);
        }
    }
}
