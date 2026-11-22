#!/system/bin/sh
# Grant persistent AppOps and Doze exemption for Link to Windows on every boot.

apply_rules() {
  for pkg in com.microsoft.appmanager com.microsoft.deviceintegrationservice com.microsoftsdk.crossdeviceservicebroker; do
    cmd appops set "$pkg" PROJECT_MEDIA allow 2>/dev/null
    cmd appops set "$pkg" TURN_SCREEN_ON allow 2>/dev/null
    cmd appops set "$pkg" RUN_ANY_IN_BACKGROUND allow 2>/dev/null
    cmd appops set "$pkg" WAKE_LOCK allow 2>/dev/null
    dumpsys deviceidle whitelist +"$pkg" >/dev/null 2>&1
  done
}

# Wait for boot completion and package service readiness.
while [ "$(getprop sys.boot_completed)" != "1" ]; do
  sleep 2
done

i=0
while [ $i -lt 15 ]; do
  if pm path com.microsoft.appmanager >/dev/null 2>&1; then
    apply_rules
    break
  fi
  i=$((i + 1))
  sleep 2
done
