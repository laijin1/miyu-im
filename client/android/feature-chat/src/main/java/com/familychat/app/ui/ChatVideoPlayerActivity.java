package com.familychat.app.ui;

import android.net.Uri;
import android.os.Bundle;
import android.widget.ImageButton;

import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import com.familychat.app.feature_chat.R;
import com.familychat.app.utils.HttpClientProvider;
import com.familychat.app.utils.PreferencesManager;
import com.familychat.app.utils.ServerProtocol;

/**
 * 聊天视频消息播放页：单个视频全屏播放（密语式，黑底 + 返回）。
 * 点击聊天框的视频缩略图进入；onPause 暂停、onDestroy 释放播放器，避免后台出声。
 */
public class ChatVideoPlayerActivity extends AppCompatActivity {

    private ExoPlayer player;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat_video_player);

        String url = getIntent().getStringExtra("url");
        // 防御性兜底: 若调用方漏拼 token, 这里补上(幂等)
        if (url != null && !url.isEmpty()) {
            url = ServerProtocol.withToken(url, PreferencesManager.getInstance(this).getToken());
        }
        PlayerView playerView = findViewById(R.id.pvChatVideo);
        ImageButton btnBack = findViewById(R.id.btnChatVideoBack);
        btnBack.setOnClickListener(v -> finish());

        // 视频 URL 是 https://miyu.lan:5000/...（ServerProtocol.baseUrl 拼的），
        // 这个名字只有 HttpClientProvider 的自定义 Dns 能解析。ExoPlayer 默认的
        // DefaultHttpDataSource 走系统 DNS → 必然 UnknownHostException。
        // 这里把数据源换成 OkHttp，复用共享 client 的 Dns 与连接池。
        // （与图片同理，见 :app 的 MiyuGlideModule。）
        OkHttpDataSource.Factory dataSource =
                new OkHttpDataSource.Factory(HttpClientProvider.shared());
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(dataSource))
                .build();
        playerView.setPlayer(player);
        if (url != null && !url.isEmpty()) {
            try {
                player.setMediaItem(MediaItem.fromUri(Uri.parse(url)));
                player.prepare();
                player.play();
            } catch (Exception e) {
                finish();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) player.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
