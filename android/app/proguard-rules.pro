# Shizuku talks to the app over a binder it looks up reflectively.
-keep class rikka.shizuku.** { *; }
# The hidden-API exemption helper is reached only through reflection on some ROMs.
-keep class org.lsposed.hiddenapibypass.** { *; }
# The control layer reflects on android.hardware.display.IDisplayManager$Stub; keep its own
# members so the transaction lookup and the writers survive shrinking.
-keep class dev.nphil.luxramp.control.** { *; }
