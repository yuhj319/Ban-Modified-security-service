# libxposed API102 入口不能被混淆
-dontwarn io.github.libxposed.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
-keep class io.github.yuhj319.banmodifiedsecurityservice.HiderEntry { *; }
