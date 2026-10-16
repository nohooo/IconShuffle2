# Shizuku 的 AIDL 与用户服务不需要混淆；debug 构建本身也不开启 minify。
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }

# UserService 由 Shizuku 通过反射构造，必须保留构造函数与入口方法
-keep class com.iconshuffle.shizuku.PrivilegedService { *; }
-keep class com.iconshuffle.shizuku.IPrivilegedService { *; }
-keep class com.iconshuffle.shizuku.IPrivilegedService$* { *; }
-keep interface com.iconshuffle.shizuku.IPrivilegedService { *; }
-keep class * implements android.os.IInterface { *; }
