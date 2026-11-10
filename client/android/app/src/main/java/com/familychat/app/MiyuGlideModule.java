package com.familychat.app;

import android.content.Context;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.module.AppGlideModule;
import com.familychat.app.utils.HttpClientProvider;

import java.io.InputStream;

/**
 * 让 Glide 走 OkHttp（而不是默认的 HttpURLConnection）。
 *
 * <h3>为什么必须要这个类</h3>
 * 服务端只监听 TLS，证书 SAN = {@code DNS:miyu.lan}，客户端所有媒体 URL 都写成
 * {@code https://miyu.lan:5000/...}（见 {@code ServerProtocol.baseUrl}）。
 * 这个名字只由 {@link HttpClientProvider} 的自定义 {@code Dns} 解析，而
 * Glide 默认用的 {@code HttpURLConnection} <b>走系统 DNS</b> —— 系统里没有
 * {@code miyu.lan}，会直接 {@code UnknownHostException}，表现为"所有图片/头像
 * 都加载不出来"。把 Glide 的取数接到共享 OkHttpClient 上，它才能继承那份 Dns。
 *
 * <h3>为什么放在 :app</h3>
 * Glide 要求进程内只能有一个 {@code AppGlideModule}，且它由注解处理器在
 * <b>应用模块</b>生成 {@code GeneratedAppGlideModule}。本仓库此前没有任何
 * GlideModule（已 grep 确认），所以这是唯一一个；:app 同时已配置
 * {@code annotationProcessor 'com.github.bumptech.glide:compiler:4.16.0'}。
 *
 * <h3>与服务器地址变更的关系</h3>
 * 这里注册的是 {@code HttpClientProvider.shared()} 返回<b>当时</b>的实例；
 * {@code refresh()} 换掉的是 Provider 的缓存，<b>换不掉</b> Glide 手里的这份快照
 * （{@code registerComponents} 每进程只跑一次），所以"重启主界面/重新登录"并不会让它
 * 重新取数 —— 只有杀进程才会。曾经因此出过真实故障：服务器 IP 被 DHCP 换掉、用户在
 * 设置里改了地址后，接口全部正常但<b>所有头像/图片都加载不出来</b>（图片请求被解析到
 * 旧 IP，服务端日志里一条都看不到）。
 * 现在这个快照是安全的：client 里的 Dns 每次解析都现取当前地址
 * （见 {@link HttpClientProvider} / {@code ServerProtocol.fakeHostDns}），
 * 不再把旧 IP 固化进闭包。改动 Dns 语义时务必保留这个性质。
 */
@GlideModule
public class MiyuGlideModule extends AppGlideModule {

    @Override
    public void registerComponents(@NonNull Context context,
                                   @NonNull Glide glide,
                                   @NonNull Registry registry) {
        registry.replace(GlideUrl.class, InputStream.class,
                new OkHttpUrlLoader.Factory(HttpClientProvider.shared()));
    }

    /**
     * 关掉 manifest 解析：本工程从无旧式 {@code <meta-data android:name=
     * "com.bumptech.glide...GlideModule">} 配置，关掉可避免注解处理器因遗留
     * 条目而报错，也少一次启动期扫描。与 {@link #registerComponents} 并用是
     * Glide 官方推荐的组合。
     */
    @Override
    public boolean isManifestParsingEnabled() {
        return false;
    }
}
