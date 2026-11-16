package com.halo.expressassistant.embedded;

/** Present in ordinary builds as well; populated only by the integrated host. */
public final class HostedCainiao {
    private static volatile ClassLoader runtime;
    private static volatile android.app.Application application;
    static void install(ClassLoader loader) { runtime = loader; }
    static void installApplication(android.app.Application app) { application = app; }
    public static boolean isAvailable() { return runtime != null; }
    public static ClassLoader runtimeLoader() {
        if(runtime == null) throw new IllegalStateException("Integrated Cainiao runtime is unavailable");
        return runtime;
    }
    public static android.content.Context runtimeContext() {
        if(application == null) throw new IllegalStateException("Integrated Cainiao application is unavailable");
        return application;
    }
    public static void login() throws Exception {
        Class<?> config=runtimeLoader().loadClass("com.cainiao.cnloginsdk.customer.sdk.config.CnmcConfig");
        if(!Boolean.TRUE.equals(config.getMethod("isInitCompleted").invoke(null))) initializeLogin(config);
        runtimeLoader().loadClass("com.cainiao.cnloginsdk.customer.sdk.CnmcLogin").getMethod("login").invoke(null);
    }
    private static void initializeLogin(Class<?> config) throws Exception {
        ClassLoader loader=runtimeLoader();
        Class<?> infoType=loader.loadClass("com.cainiao.cnloginsdk.customer.sdk.config.CnmcConfigInfo");
        Object info=infoType.getDeclaredConstructor().newInstance();
        infoType.getMethod("setContext",android.content.Context.class).invoke(info,application);
        Class<?> environment=loader.loadClass("com.cainiao.cnloginsdk.customer.sdk.enums.CnmcEnvEnum");
        infoType.getMethod("setEnvEnum",environment).invoke(info,environment.getField("ONLINE").get(null));
        String ttid=(String)loader.loadClass("com.cainiao.wireless.utils.AppUtils").getMethod("getTTID",android.content.Context.class).invoke(null,application);
        infoType.getMethod("setTtid",String.class).invoke(info,ttid);
        infoType.getMethod("setProductVersion",String.class).invoke(info,"1.0.0");
        Class<?> providerType=loader.loadClass("com.cainiao.cnloginsdk.customer.sdk.config.CnmcDefaultAppProvider");
        Object provider=loader.loadClass("com.cainiao.wireless.login.provider.CustomerAppProvider").getDeclaredConstructor().newInstance();
        infoType.getMethod("setAppProvider",providerType).invoke(info,provider);
        Class<?> tenant=loader.loadClass("com.cainiao.cnloginsdk.customer.sdk.enums.CnmTenantEnum");
        infoType.getMethod("setTenant",tenant).invoke(info,tenant.getField("CAINIAO_C").get(null));
        infoType.getMethod("setCnMtopInstantId",String.class).invoke(info,"cainiao2c");
        config.getMethod("init",infoType).invoke(null,info);
        config.getMethod("configLoginApprearanceExtensions",loader.loadClass("com.ali.user.mobile.common.api.LoginApprearanceExtensions")).invoke(null,new Object[]{null});
        android.util.Log.i("EA-CainiaoHost","embedded_login_initialized");
    }
    static void deliverSession(Object session) {
        if(session==null || application==null) return;
        try {
            ClassLoader loader=runtimeLoader();
            String raw=(String)loader.loadClass("com.alibaba.fastjson.JSON").getMethod("toJSONString",Object.class).invoke(null,session);
            String utdid=(String)loader.loadClass("com.ut.device.UTDevice").getMethod("getUtdid",android.content.Context.class).invoke(null,application);
            Object config=loader.loadClass("com.cainiao.cnloginsdk.customer.sdk.config.CnmcConfig").getMethod("getConfigInfo").invoke(null);
            String ttid=(String)config.getClass().getMethod("getTtid").invoke(config);
            org.json.JSONObject device=new org.json.JSONObject().put("utdid",utdid).put("ttid",ttid);
            EmbeddedCainiaoLogin.acceptSdkSession(raw,device.toString());
        } catch(Throwable ignored) { EmbeddedCainiaoLogin.sessionUnavailable(); }
    }
    private HostedCainiao() {}
}
