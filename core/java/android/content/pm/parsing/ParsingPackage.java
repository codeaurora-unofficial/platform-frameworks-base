/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.content.pm.parsing;

import android.annotation.Nullable;
import android.content.Intent;
import android.content.pm.ConfigurationInfo;
import android.content.pm.FeatureGroupInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageParser;
import android.content.pm.parsing.ComponentParseUtils.ParsedActivity;
import android.content.pm.parsing.ComponentParseUtils.ParsedActivityIntentInfo;
import android.content.pm.parsing.ComponentParseUtils.ParsedInstrumentation;
import android.content.pm.parsing.ComponentParseUtils.ParsedPermission;
import android.content.pm.parsing.ComponentParseUtils.ParsedPermissionGroup;
import android.content.pm.parsing.ComponentParseUtils.ParsedProvider;
import android.content.pm.parsing.ComponentParseUtils.ParsedService;
import android.os.Bundle;
import android.util.ArraySet;
import android.util.SparseArray;

import java.security.PublicKey;

/**
 * Methods used for mutation during direct package parsing.
 *
 * Java disallows defining this as an inner interface, so this must be a separate file.
 *
 * @hide
 */
public interface ParsingPackage extends AndroidPackage {

    ParsingPackage addActivity(ParsedActivity parsedActivity);

    ParsingPackage addAdoptPermission(String adoptPermission);

    ParsingPackage addConfigPreference(ConfigurationInfo configPreference);

    ParsingPackage addFeatureGroup(FeatureGroupInfo featureGroup);

    ParsingPackage addImplicitPermission(String permission);

    ParsingPackage addInstrumentation(ParsedInstrumentation instrumentation);

    ParsingPackage addKeySet(String keySetName, PublicKey publicKey);

    ParsingPackage addLibraryName(String libraryName);

    ParsingPackage addOriginalPackage(String originalPackage);

    ParsingPackage addPermission(ParsedPermission permission);

    ParsingPackage addPermissionGroup(ParsedPermissionGroup permissionGroup);

    ParsingPackage addPreferredActivityFilter(ParsedActivityIntentInfo activityIntentInfo);

    ParsingPackage addProtectedBroadcast(String protectedBroadcast);

    ParsingPackage addProvider(ParsedProvider parsedProvider);

    ParsingPackage addReceiver(ParsedActivity parsedReceiver);

    ParsingPackage addReqFeature(FeatureInfo reqFeature);

    ParsingPackage addRequestedPermission(String permission);

    ParsingPackage addService(ParsedService parsedService);

    ParsingPackage addUsesLibrary(String libraryName);

    ParsingPackage addUsesOptionalLibrary(String libraryName);

    ParsingPackage addUsesStaticLibrary(String libraryName);

    ParsingPackage addUsesStaticLibraryCertDigests(String[] certSha256Digests);

    ParsingPackage addUsesStaticLibraryVersion(long version);

    ParsingPackage addQueriesIntent(Intent intent);

    ParsingPackage addQueriesPackage(String packageName);

    ParsingPackage asSplit(
            String[] splitNames,
            String[] splitCodePaths,
            int[] splitRevisionCodes,
            @Nullable SparseArray<int[]> splitDependencies
    );

    ParsingPackage setAppMetaData(Bundle appMetaData);

    ParsingPackage setForceQueryable(boolean forceQueryable);

    ParsingPackage setMaxAspectRatio(float maxAspectRatio);

    ParsingPackage setMinAspectRatio(float minAspectRatio);

    ParsingPackage setName(String name);

    ParsingPackage setPermission(String permission);

    ParsingPackage setProcessName(String processName);

    ParsingPackage setSharedUserId(String sharedUserId);

    ParsingPackage setStaticSharedLibName(String staticSharedLibName);

    ParsingPackage setTaskAffinity(String taskAffinity);

    ParsingPackage setTargetSdkVersion(int targetSdkVersion);

    ParsingPackage setUiOptions(int uiOptions);

    ParsingPackage setBaseHardwareAccelerated(boolean baseHardwareAccelerated);

    ParsingPackage setActivitiesResizeModeResizeable(boolean resizeable);

    ParsingPackage setActivitiesResizeModeResizeableViaSdkVersion(boolean resizeableViaSdkVersion);

    ParsingPackage setAllowAudioPlaybackCapture(boolean allowAudioPlaybackCapture);

    ParsingPackage setAllowBackup(boolean allowBackup);

    ParsingPackage setAllowClearUserData(boolean allowClearUserData);

    ParsingPackage setAllowClearUserDataOnFailedRestore(boolean allowClearUserDataOnFailedRestore);

    ParsingPackage setAllowTaskReparenting(boolean allowTaskReparenting);

    ParsingPackage setIsOverlay(boolean isOverlay);

    ParsingPackage setBackupInForeground(boolean backupInForeground);

    ParsingPackage setCantSaveState(boolean cantSaveState);

    ParsingPackage setDebuggable(boolean debuggable);

    ParsingPackage setDefaultToDeviceProtectedStorage(boolean defaultToDeviceProtectedStorage);

    ParsingPackage setDirectBootAware(boolean directBootAware);

    ParsingPackage setExternalStorage(boolean externalStorage);

    ParsingPackage setExtractNativeLibs(boolean extractNativeLibs);

    ParsingPackage setFullBackupOnly(boolean fullBackupOnly);

    ParsingPackage setHasCode(boolean hasCode);

    ParsingPackage setHasFragileUserData(boolean hasFragileUserData);

    ParsingPackage setIsGame(boolean isGame);

    ParsingPackage setIsolatedSplitLoading(boolean isolatedSplitLoading);

    ParsingPackage setKillAfterRestore(boolean killAfterRestore);

    ParsingPackage setLargeHeap(boolean largeHeap);

    ParsingPackage setMultiArch(boolean multiArch);

    ParsingPackage setPartiallyDirectBootAware(boolean partiallyDirectBootAware);

    ParsingPackage setPersistent(boolean persistent);

    ParsingPackage setProfileableByShell(boolean profileableByShell);

    ParsingPackage setRequestLegacyExternalStorage(boolean requestLegacyExternalStorage);

    ParsingPackage setRestoreAnyVersion(boolean restoreAnyVersion);

    ParsingPackage setSplitHasCode(int splitIndex, boolean splitHasCode);

    ParsingPackage setStaticSharedLibrary(boolean staticSharedLibrary);

    ParsingPackage setSupportsRtl(boolean supportsRtl);

    ParsingPackage setTestOnly(boolean testOnly);

    ParsingPackage setUseEmbeddedDex(boolean useEmbeddedDex);

    ParsingPackage setUsesCleartextTraffic(boolean usesCleartextTraffic);

    ParsingPackage setUsesNonSdkApi(boolean usesNonSdkApi);

    ParsingPackage setVisibleToInstantApps(boolean visibleToInstantApps);

    ParsingPackage setVmSafeMode(boolean vmSafeMode);

    ParsingPackage removeUsesOptionalLibrary(String libraryName);

    ParsingPackage setAnyDensity(int anyDensity);

    ParsingPackage setAppComponentFactory(String appComponentFactory);

    ParsingPackage setApplicationVolumeUuid(String applicationVolumeUuid);

    ParsingPackage setBackupAgentName(String backupAgentName);

    ParsingPackage setBanner(int banner);

    ParsingPackage setCategory(int category);

    ParsingPackage setClassLoaderName(String classLoaderName);

    ParsingPackage setClassName(String className);

    ParsingPackage setCodePath(String codePath);

    ParsingPackage setCompatibleWidthLimitDp(int compatibleWidthLimitDp);

    ParsingPackage setDescriptionRes(int descriptionRes);

    ParsingPackage setEnabled(boolean enabled);

    ParsingPackage setFullBackupContent(int fullBackupContent);

    ParsingPackage setHasDomainUrls(boolean hasDomainUrls);

    ParsingPackage setIcon(int icon);

    ParsingPackage setIconRes(int iconRes);

    ParsingPackage setInstallLocation(int installLocation);

    ParsingPackage setLabelRes(int labelRes);

    ParsingPackage setLargestWidthLimitDp(int largestWidthLimitDp);

    ParsingPackage setLogo(int logo);

    ParsingPackage setManageSpaceActivityName(String manageSpaceActivityName);

    ParsingPackage setMinSdkVersion(int minSdkVersion);

    ParsingPackage setNetworkSecurityConfigRes(int networkSecurityConfigRes);

    ParsingPackage setNonLocalizedLabel(CharSequence nonLocalizedLabel);

    ParsingPackage setOverlayCategory(String overlayCategory);

    ParsingPackage setOverlayIsStatic(boolean overlayIsStatic);

    ParsingPackage setOverlayPriority(int overlayPriority);

    ParsingPackage setOverlayTarget(String overlayTarget);

    ParsingPackage setOverlayTargetName(String overlayTargetName);

    ParsingPackage setRealPackage(String realPackage);

    ParsingPackage setRequiredAccountType(String requiredAccountType);

    ParsingPackage setRequiredForAllUsers(boolean requiredForAllUsers);

    ParsingPackage setRequiresSmallestWidthDp(int requiresSmallestWidthDp);

    ParsingPackage setResizeable(int resizeable);

    ParsingPackage setRestrictUpdateHash(byte[] restrictUpdateHash);

    ParsingPackage setRestrictedAccountType(String restrictedAccountType);

    ParsingPackage setRoundIconRes(int roundIconRes);

    ParsingPackage setSharedUserLabel(int sharedUserLabel);

    ParsingPackage setSigningDetails(PackageParser.SigningDetails signingDetails);

    ParsingPackage setSplitClassLoaderName(int splitIndex, String classLoaderName);

    ParsingPackage setStaticSharedLibVersion(long staticSharedLibVersion);

    ParsingPackage setSupportsLargeScreens(int supportsLargeScreens);

    ParsingPackage setSupportsNormalScreens(int supportsNormalScreens);

    ParsingPackage setSupportsSmallScreens(int supportsSmallScreens);

    ParsingPackage setSupportsXLargeScreens(int supportsXLargeScreens);

    ParsingPackage setTargetSandboxVersion(int targetSandboxVersion);

    ParsingPackage setTheme(int theme);

    ParsingPackage setUpgradeKeySets(ArraySet<String> upgradeKeySets);

    ParsingPackage setUse32BitAbi(boolean use32BitAbi);

    ParsingPackage setVolumeUuid(String volumeUuid);

    ParsingPackage setZygotePreloadName(String zygotePreloadName);

    ParsingPackage sortActivities();

    ParsingPackage sortReceivers();

    ParsingPackage sortServices();

    ParsedPackage hideAsParsed();

    ParsingPackage setBaseRevisionCode(int baseRevisionCode);

    ParsingPackage setPreferredOrder(int preferredOrder);

    ParsingPackage setVersionName(String versionName);

    ParsingPackage setCompileSdkVersion(int compileSdkVersion);

    ParsingPackage setCompileSdkVersionCodename(String compileSdkVersionCodename);

    boolean usesCompatibilityMode();
}
