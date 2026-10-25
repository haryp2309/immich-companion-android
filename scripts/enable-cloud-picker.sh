#!/bin/sh
# Allows Immich Companion as a cloud media app in the Android photo picker.
#
# The picker only offers allowlisted cloud providers, and the allowlist is a
# device_config flag that only adb (or the device vendor) can change. After
# running this, open the photo picker, tap the menu (⋮) > Browse/Settings >
# "Cloud media app" and choose Immich Companion.
#
# Usage: scripts/enable-cloud-picker.sh [adb device serial]
set -eu

AUTHORITY=app.immichcompanion.cloudpicker
ADB="adb${1:+ -s $1}"

current=$($ADB shell device_config get mediaprovider allowed_cloud_providers | tr -d '\r')
case ",$current," in
  *",$AUTHORITY,"*) providers=$current ;;
  ,null,|,,) providers=$AUTHORITY ;;
  *) providers="$current,$AUTHORITY" ;;
esac

# Keep Google Play services from resetting the flags with its own values.
$ADB shell device_config set_sync_disabled_for_tests persistent
$ADB shell device_config put mediaprovider cloud_media_feature_enabled true
$ADB shell device_config put mediaprovider allowed_cloud_providers "$providers"
# The picker reads the flags when MediaProvider starts.
$ADB shell am force-stop com.google.android.providers.media.module 2>/dev/null || true
$ADB shell am force-stop com.android.providers.media.module 2>/dev/null || true

echo "Allowed cloud providers: $($ADB shell device_config get mediaprovider allowed_cloud_providers | tr -d '\r')"
echo "Now open the photo picker and choose Immich Companion under Cloud media app."
