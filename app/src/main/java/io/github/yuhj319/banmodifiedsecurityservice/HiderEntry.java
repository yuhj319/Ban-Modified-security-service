package io.github.yuhj319.banmodifiedsecurityservice;

import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 干掉HyperOS4官改非官方内容，LibXposed API 102，无 UI。
 *
 * <p>复刻 BatteryInfoHider v2.4（BSH 插件）的静态实现，不带 UI、不读外部 config：
 * 目标 {@code com.miui.powercenter.nightcharge.ChargeProtectFragment}，
 * hook {@code onCreatePreferences / getBatteryHealthInfo / getDesignCapacity / getActualCapacity}。
 *
 * <p>默认策略与 {@code config.txt} 全 {@code @remove} 一致：percentc/designc/actualc 三行删掉。
 * 想改值：把对应 key 从 REMOVE 拿掉，并设置下面的 OVERRIDE_* 常量后重编。
 */
public class HiderEntry extends XposedModule {

    private static final String TAG = "BanModified";

    private static final String FRAG_CLS = "com.miui.powercenter.nightcharge.ChargeProtectFragment";

    private static final Set<String> TARGET_PKGS = new HashSet<>(Arrays.asList(
            "com.miui.securitycenter",  // 安全中心（主宿主，ChargeProtectFragment 实际所在进程）
            "com.miui.powerkeeper",     // 部分 ROM 电池页迁移到此处，兜底
            "com.miui.powercenter"      // 极少数 ROM 独立包名，兜底
    ));

    // ===== 策略区（改这里后重编即可，无需 UI / 无需外部文件）=====
    /** 要从偏好树删掉的 key，对应 config.txt 的 @remove。 */
    private static final Set<String> REMOVE_KEYS = new HashSet<>(Arrays.asList(
            "percentc", "designc", "actualc"
    ));
    /** 覆盖值：null = 保持真实值。percentc 允许任意文本。 */
    private static final String OVERRIDE_PERCENT = null; // 例如 "100%"
    /** designc/actualc 只允许“数字+mAh”，否则忽略（与原插件一致，防止页面崩溃）。 */
    private static final String OVERRIDE_DESIGN = null;  // 例如 "5000mAh"
    private static final String OVERRIDE_ACTUAL = null;  // 例如 "5000mAh"
    // ===========================================================

    private volatile boolean hooked = false;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        super.onModuleLoaded(param);
        log(Log.INFO, TAG, "loaded in " + param.getProcessName()
                + ", api=" + getApiVersion() + " framework=" + getFrameworkName());
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        super.onPackageLoaded(param);
        String pkg = param.getPackageName();
        if (!TARGET_PKGS.contains(pkg)) {
            return;
        }
        log(Log.INFO, TAG, "packageLoaded pkg=" + pkg
                + " first=" + param.isFirstPackage());
        tryHook(param.getDefaultClassLoader(), "packageLoaded");
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        super.onPackageReady(param);
        // onPackageLoaded 时类可能还没进默认 loader，这里做一次兜底。
        // PackageReadyParam 没有 getPackageName，只有 isFirstPackage + classloader，
        // 所以只在尚未 hook 成功时重试，不做包名过滤（onPackageLoaded 已过滤）。
        if (!hooked) {
            tryHook(param.getClassLoader(), "packageReady");
        }
    }

    private synchronized void tryHook(ClassLoader cl, String stage) {
        if (hooked) return;
        final Class<?> frag;
        try {
            frag = Class.forName(FRAG_CLS, false, cl);
        } catch (ClassNotFoundException e) {
            log(Log.DEBUG, TAG, "[" + stage + "] " + FRAG_CLS + " not in this loader, skip");
            return;
        }
        log(Log.INFO, TAG, "[" + stage + "] target=" + frag);
        int installed = 0;
        for (Method m : frag.getDeclaredMethods()) {
            String name = m.getName();
            try {
                if ("onCreatePreferences".equals(name)) {
                    hook(m).intercept(chain -> {
                        Object r = chain.proceed();
                        try {
                            pruneAfterCreate(chain.getThisObject());
                        } catch (Throwable t) {
                            log(Log.WARN, TAG, "prune failed: " + t);
                        }
                        return r;
                    });
                    installed++;
                    log(Log.INFO, TAG, "[A] onCreatePreferences hooked: " + m);
                } else if ("getBatteryHealthInfo".equals(name)) {
                    hook(m).intercept(chain -> {
                        Object orig = chain.proceed();
                        if (OVERRIDE_PERCENT != null && OVERRIDE_PERCENT.length() > 0) {
                            log(Log.INFO, TAG, "[B1] getBatteryHealthInfo -> " + OVERRIDE_PERCENT
                                    + " (orig=" + orig + ")");
                            return OVERRIDE_PERCENT;
                        }
                        return orig;
                    });
                    installed++;
                    log(Log.INFO, TAG, "[B1] getBatteryHealthInfo hooked: " + m);
                } else if ("getDesignCapacity".equals(name)) {
                    hook(m).intercept(chain -> {
                        Object orig = chain.proceed();
                        if (OVERRIDE_DESIGN != null && isNumCap(OVERRIDE_DESIGN)) {
                            log(Log.INFO, TAG, "[B2] getDesignCapacity -> " + OVERRIDE_DESIGN
                                    + " (orig=" + orig + ")");
                            return OVERRIDE_DESIGN;
                        }
                        return orig;
                    });
                    installed++;
                    log(Log.INFO, TAG, "[B2] getDesignCapacity hooked: " + m);
                } else if ("getActualCapacity".equals(name)) {
                    hook(m).intercept(chain -> {
                        Object orig = chain.proceed();
                        if (OVERRIDE_ACTUAL != null && isNumCap(OVERRIDE_ACTUAL)) {
                            log(Log.INFO, TAG, "[B3] getActualCapacity -> " + OVERRIDE_ACTUAL
                                    + " (orig=" + orig + ")");
                            return OVERRIDE_ACTUAL;
                        }
                        return orig;
                    });
                    installed++;
                    log(Log.INFO, TAG, "[B3] getActualCapacity hooked: " + m);
                }
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "hook " + name + " failed: " + t, t);
            }
        }
        if (installed > 0) {
            hooked = true;
            log(Log.INFO, TAG, "installed " + installed + " hooks on " + FRAG_CLS);
        } else {
            log(Log.WARN, TAG, "no target methods found in " + FRAG_CLS);
        }
    }

    /** onCreatePreferences 之后：递归删偏好树，只做 @remove（与原 prune() 等价，全反射避免类加载器冲突）。 */
    private void pruneAfterCreate(Object fragObj) throws Exception {
        if (REMOVE_KEYS.isEmpty()) return;
        Object screen = fragObj.getClass().getMethod("getPreferenceScreen").invoke(fragObj);
        if (screen == null) {
            log(Log.WARN, TAG, "preferenceScreen null, skip prune");
            return;
        }
        int cnt = pruneGroup(screen, 0);
        log(Log.INFO, TAG, "prune done, removed " + cnt);
    }

    private int pruneGroup(Object group, int depth) {
        int done = 0;
        try {
            int n = (int) group.getClass().getMethod("getPreferenceCount").invoke(group);
            // PreferenceGroup 实际类型来自宿主 loader，用名字找 removePreference(1参) 方法
            Method removeM = null;
            for (Method m : group.getClass().getMethods()) {
                if ("removePreference".equals(m.getName()) && m.getParameterTypes().length == 1) {
                    removeM = m;
                    break;
                }
            }
            Method getM = group.getClass().getMethod("getPreference", int.class);
            // androidx.preference.PreferenceGroup 在宿主 loader 里，isInstance 判断分组
            Class<?> groupCls = Class.forName("androidx.preference.PreferenceGroup",
                    false, group.getClass().getClassLoader());
            for (int i = n - 1; i >= 0; i--) {
                Object item;
                try {
                    item = getM.invoke(group, i);
                } catch (Throwable t) {
                    continue;
                }
                if (item == null) continue;
                String key = null;
                try {
                    key = (String) item.getClass().getMethod("getKey").invoke(item);
                } catch (Throwable ignored) {
                }
                if (key != null && REMOVE_KEYS.contains(key.trim())) {
                    try {
                        boolean ok = removeM != null && (boolean) removeM.invoke(group, item);
                        if (ok) {
                            done++;
                            log(Log.INFO, TAG, "removed key=" + key);
                        }
                    } catch (Throwable t) {
                        log(Log.WARN, TAG, "remove key=" + key + " failed: " + t);
                    }
                    continue;
                }
                try {
                    if (groupCls.isInstance(item)) {
                        done += pruneGroup(item, depth + 1);
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "pruneGroup failed: " + t);
        }
        return done;
    }

    /** 仅“数字+mAh”才允许覆盖容量（与原 isNumCap 一致）。 */
    private static boolean isNumCap(String cap) {
        if (cap == null || cap.startsWith("@")) return false;
        String num = cap.toLowerCase().replace("mah", "").trim();
        if (num.isEmpty()) return false;
        try {
            Float.parseFloat(num);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
