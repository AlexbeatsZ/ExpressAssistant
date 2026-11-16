package com.halo.expressassistant.embedded;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.app.Service;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.ComponentCallbacks;
import android.content.Intent;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.util.Log;
import android.view.LayoutInflater;
import java.util.Map;
import java.util.WeakHashMap;
import androidx.work.Configuration;
import androidx.work.WorkManager;
import com.halo.expressassistant.App;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Keeps the original runtime context intact while hosting ExpressAssistant components. */
public final class CainiaoHostHook implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    private static String modulePath;
    private static volatile App application;
    private static volatile Resources moduleResources;
    private static final Map<Activity, Integer> activityNightModes = new WeakHashMap<>();
    private static final String TAG = "EA-CainiaoHost";

    @Override public void initZygote(StartupParam param) { modulePath = param.modulePath; }

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) throws Throwable {
        if (android.os.Build.VERSION.SDK_INT < 28) return;
        if (!param.packageName.equals("com.halo.expressassistant")) return;
        ClassLoader ownLoader = CainiaoHostHook.class.getClassLoader();
        HostedCainiao.install(param.classLoader);
        XC_MethodHook suppressCredentials = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                for(Object value:p.args) if(value instanceof String) {
                    String text=(String)value;
                    if(text.contains("sessionId=") || text.contains("refreshToken") || text.contains("cnMobileSid=") || text.contains("havana token:") || text.contains("\"sessionId\"")) {
                        p.setResult(p.method instanceof java.lang.reflect.Method && ((java.lang.reflect.Method)p.method).getReturnType()==int.class ? 0 : null);
                        return;
                    }
                }
            }
        };
        for(String method:new String[]{"i","d","v","w","e","println"}) XposedBridge.hookAllMethods(Log.class,method,suppressCredentials);
        Class<?> sessions=param.classLoader.loadClass("com.cainiao.cnloginsdk.customer.x.LoginInfoContainer");
        XposedBridge.hookAllMethods(sessions, "saveSessionInfo", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) { HostedCainiao.deliverSession(p.args[0]); }
        });
        XposedBridge.hookAllMethods(Instrumentation.class, "callApplicationOnCreate", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (param.processName.equals(param.packageName)) {
                    HostedCainiao.installApplication((Application)p.args[0]);
                    ensureApp((Application)p.args[0]);
                }
            }
        });
        XposedHelpers.findAndHookMethod(Instrumentation.class, "newActivity", ClassLoader.class, String.class, Intent.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                String name = (String)p.args[1];
                if (name.equals("com.cainiao.wireless.homepage.view.activity.WelcomeActivity") ||
                    name.equals("com.cainiao.wireless.homepage.view.activity.HomePageActivity") ||
                    name.equals("com.cainiao.wireless.homepage.view.activity.AdsActivity") ||
                    name.equals("com.cainiao.wireless.homepage.splash.preview.SplashAdsPreviewDownloadActivity"))
                    name = "com.halo.expressassistant.ui.MainActivity";
                if (isOwn(name)) p.setResult(ownLoader.loadClass(name).getDeclaredConstructor().newInstance());
            }
        });
        XposedBridge.hookAllMethods(Activity.class, "attach", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!isOwn(p.thisObject.getClass().getName())) return;
                Context base = (Context)p.args[0]; App app = ensureApp(base);
                p.args[0] = new ModuleContext(base, app);
                if (android.os.Build.VERSION.SDK_INT < 30)
                    activityNightModes.put((Activity)p.thisObject, moduleResources.getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK);
                for (int i=0;i<p.args.length;i++) if (p.args[i] instanceof Application) p.args[i] = app;
            }
        });
        if (android.os.Build.VERSION.SDK_INT < 30) XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (isOwn(p.thisObject.getClass().getName())) {
                    Activity activity=(Activity)p.thisObject;
                    activity.getWindow().getDecorView().post(() -> recreateForNightChange(activity));
                }
            }
        });
        for (String method : new String[]{"instantiateService", "instantiateReceiver", "instantiateProvider"}) {
            XposedBridge.hookAllMethods(android.app.AppComponentFactory.class, method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                    String name = (String)p.args[1];
                    if (isOwn(name) || name.startsWith("androidx.work.") || name.startsWith("androidx.profileinstaller.") || name.startsWith("androidx.room.") || name.startsWith("rikka.shizuku."))
                        p.setResult(ownLoader.loadClass(name).getDeclaredConstructor().newInstance());
                }
            });
        }
        XposedBridge.hookAllMethods(Service.class, "attach", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                String name = p.thisObject.getClass().getName();
                if (!isOwn(name) && !name.startsWith("androidx.work.")) return;
                Context base = (Context)p.args[0]; App app = ensureApp(base); p.args[0] = new ModuleContext(base, app);
                for(int i=0;i<p.args.length;i++) if(p.args[i] instanceof Application) p.args[i]=app;
            }
        });
        Log.i(TAG, "host_hooks_installed process=" + param.processName);
    }

    private static boolean isOwn(String name) { return name.startsWith("com.halo.expressassistant."); }

    private static void recreateForNightChange(Activity activity) {
        if (activity==null || activity.isFinishing() || activity.isDestroyed()) return;
        int current=moduleResources.getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        Integer previous=activityNightModes.get(activity);
        if(previous!=null && previous!=current) {
            activityNightModes.put(activity,current);
            activity.recreate();
        }
    }

    private static synchronized App ensureApp(Context base) {
        if (application != null) return application;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                moduleResources = Api30.resources(base);
            } else {
                AssetManager assets = AssetManager.class.getDeclaredConstructor().newInstance();
                AssetManager.class.getMethod("addAssetPath", String.class).invoke(assets, modulePath);
                moduleResources = new Resources(assets, base.getResources().getDisplayMetrics(), base.getResources().getConfiguration());
                HostedCainiao.runtimeContext().registerComponentCallbacks(new ComponentCallbacks() {
                    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
                        moduleResources.updateConfiguration(configuration, base.getResources().getDisplayMetrics());
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> recreateForNightChange(App.Companion.getTopActivity()));
                    }
                    @Override public void onLowMemory() {}
                });
            }
            App app = new App(); application = app;
            ModuleContext context = new ModuleContext(base, app);
            XposedHelpers.callMethod(app, "attach", context);
            WorkManager.initialize(app, new Configuration.Builder().build());
            app.onCreate();
            Log.i(TAG, "login_sdk_ready=" + HostedCainiao.runtimeLoader().loadClass("com.cainiao.cnloginsdk.customer.sdk.config.CnmcConfig").getMethod("isInitCompleted").invoke(null));
            Log.i(TAG, "express_application_ready");
            return app;
        } catch (Throwable error) {
            application = null;
            Log.e(TAG, "express_application_failed", error);
            throw new IllegalStateException("ExpressAssistant host initialization failed", error);
        }
    }

    private static final class ModuleContext extends ContextWrapper {
        private final App application;
        private final Resources resources;
        private Resources.Theme theme;
        private android.content.res.Configuration themeConfiguration;
        ModuleContext(Context base, App app) {
            super(base); application = app;
            if (android.os.Build.VERSION.SDK_INT >= 30) resources = Api30.resources(base);
            else {
                moduleResources.updateConfiguration(base.getResources().getConfiguration(), base.getResources().getDisplayMetrics());
                resources = moduleResources;
            }
        }
        @Override public Resources getResources() { return resources; }
        @Override public AssetManager getAssets() { return resources.getAssets(); }
        @Override public ClassLoader getClassLoader() { return CainiaoHostHook.class.getClassLoader(); }
        @Override public Context getApplicationContext() { return application; }
        @Override public Resources.Theme getTheme() {
            if (theme == null || !resources.getConfiguration().equals(themeConfiguration)) {
                theme = resources.newTheme();
                theme.applyStyle(com.halo.expressassistant.R.style.Theme_ExpressAssistant, true);
                themeConfiguration = new android.content.res.Configuration(resources.getConfiguration());
            }
            return theme;
        }
        @Override public void setTheme(int id) { getTheme().applyStyle(id, true); }
        @Override public Object getSystemService(String name) {
            if (LAYOUT_INFLATER_SERVICE.equals(name)) return LayoutInflater.from(getBaseContext()).cloneInContext(this);
            return super.getSystemService(name);
        }
    }

    @android.annotation.TargetApi(30)
    private static final class Api30 {
        private static android.content.res.loader.ResourcesLoader loader;
        static Resources resources(Context context) {
            Resources resources=context.getResources();
            try {
                resources.getResourcePackageName(com.halo.expressassistant.R.string.app_name);
                return resources;
            } catch(Resources.NotFoundException ignored) {}
            try {
                if(loader==null) {
                    loader=new android.content.res.loader.ResourcesLoader();
                    try(android.os.ParcelFileDescriptor descriptor=android.os.ParcelFileDescriptor.open(new java.io.File(modulePath),android.os.ParcelFileDescriptor.MODE_READ_ONLY)) {
                        loader.addProvider(android.content.res.loader.ResourcesProvider.loadFromApk(descriptor));
                    }
                }
                resources.addLoaders(loader);
                resources.getResourcePackageName(com.halo.expressassistant.R.string.app_name);
                return resources;
            } catch(java.io.IOException error) { throw new IllegalStateException("Cannot load integrated interface resources",error); }
        }
    }
}
