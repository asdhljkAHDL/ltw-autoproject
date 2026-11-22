SKIPUNZIP=0

ui_print "- Installing Link to Windows Auto Project Helper..."
ui_print "- Applying AppOps (PROJECT_MEDIA / TURN_SCREEN_ON) for Link to Windows..."

for pkg in com.microsoft.appmanager com.microsoft.deviceintegrationservice; do
  cmd appops set "$pkg" PROJECT_MEDIA allow 2>/dev/null
  cmd appops set "$pkg" TURN_SCREEN_ON allow 2>/dev/null
  cmd appops set "$pkg" RUN_ANY_IN_BACKGROUND allow 2>/dev/null
  dumpsys deviceidle whitelist +"$pkg" >/dev/null 2>&1
done

set_perm "$MODPATH/service.sh" 0 0 0755
ui_print "- Done! Enable LTW Auto Project in LSPosed Manager for full lockscreen casting."
