# 默认规则；Compose / Kotlin metadata 由 AGP 默认处理。
-dontwarn org.jetbrains.annotations.**

# ---------------------------------------------------------------------------
# ROOT 守护进程必须原样保留
#
# 它不是在 App 进程里 new 出来的对象，而是由 su 以
#   CLASSPATH=<apk> app_process /system/bin com.volumeboost.app.daemon.RootDaemon
# 的方式按「类名」反射启动的。R8 看不到这条引用，默认会把整个类删掉或改名，
# release 包就会变成「守护进程起不来」。这里把类名和 main 方法钉死。
# ---------------------------------------------------------------------------
-keep class com.volumeboost.app.daemon.RootDaemon {
    public static void main(java.lang.String[]);
}
-keepclassmembers class com.volumeboost.app.daemon.RootDaemon {
    <fields>;
    <methods>;
}
