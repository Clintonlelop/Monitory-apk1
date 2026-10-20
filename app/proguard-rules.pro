# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# --- Release crash fix: ProfileInstaller startup path -------------------------
# androidx.profileinstaller.ProfileInstallerInitializer runs on every app
# start and ProfileInstaller.writeProfile() -> ProfileVerifier statically
# initializes androidx.concurrent.futures.ResolvableFuture, whose superclass
# AbstractResolvableFuture implements com.google.common.util.concurrent.
# ListenableFuture. These types are not referenced from app code, so R8 can
# shrink or class-merge them away, producing:
#   java.lang.NoClassDefFoundError: Failed resolution of:
#     Landroidx/concurrent/futures/AbstractResolvableFuture;
#     at androidx.profileinstaller.ProfileInstaller.writeProfile(...)
# Keep the full futures runtime and the ListenableFuture interface they
# implement. (The companion dependency pins live in app/build.gradle.kts.)
-keep class androidx.concurrent.futures.** { *; }
-keep interface com.google.common.util.concurrent.ListenableFuture { *; }

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
