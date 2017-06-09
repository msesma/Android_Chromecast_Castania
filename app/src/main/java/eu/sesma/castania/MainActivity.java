package eu.sesma.castania;

import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManager;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;

import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.MediaStore;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.app.MediaRouteButton;
import android.text.format.Formatter;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import eu.sesma.castania.castserver.CastServerService;

public class MainActivity extends AppCompatActivity {
    // https://github.com/googlecast/CastCompanionLibrary-android/blob/master/CastCompanionLibrary.pdf
    // https://developers.google.com/cast/docs/sender_apps

    private static final String TAG = "Castania_Main";
    private static String mediaName = "";
    private Button selectButtonVideo, selectButtonPhoto, serverButtonLight;
    private MediaRouteButton mediaRouteButton;
    private static boolean photoMode;

    private CastContext castContext;
    private CastSession castSession;
    private SessionManager sessionManager;
    private RemoteMediaClient remoteMediaClient;

    private RemoteMediaClient.Listener clientListener = new RemoteMediaClient.Listener() {
        @Override
        public void onStatusUpdated() {
            Intent intent = new Intent(MainActivity.this, ExpandedControlsActivity.class);
            startActivity(intent);
            remoteMediaClient.removeListener(this);
        }

        @Override
        public void onMetadataUpdated() {
        }

        @Override
        public void onQueueStatusUpdated() {
        }

        @Override
        public void onPreloadStatusUpdated() {
        }

        @Override
        public void onSendingRemoteMediaRequest() {
        }

        @Override
        public void onAdBreakStatusUpdated() {

        }
    };

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ActionBar actionBar = getSupportActionBar();
        actionBar.setBackgroundDrawable(new ColorDrawable(ContextCompat.getColor(this, android.R.color.transparent)));

        mediaRouteButton = (MediaRouteButton) findViewById(R.id.media_route_button);
        CastButtonFactory.setUpMediaRouteButton(getApplicationContext(), mediaRouteButton);
        castContext = CastContext.getSharedInstance(this);
        sessionManager = castContext.getSessionManager();

        //TODO Butterknife
        selectButtonVideo = (Button) findViewById(R.id.bt_select_video);
        selectButtonPhoto = (Button) findViewById(R.id.bt_select_photo);
        serverButtonLight = (Button) findViewById(R.id.bt_light);
        selectButtonVideo.setEnabled(false);
        selectButtonPhoto.setEnabled(false);
        ((LinearLayout) findViewById(R.id.ll_media_buttons)).setVisibility(View.INVISIBLE);
        stopCastServer();
    }

    @Override
    protected void onResume() {
        castSession = sessionManager.getCurrentCastSession();
        super.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        castSession = null;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.main, menu);
        CastButtonFactory.setUpMediaRouteButton(getApplicationContext(), menu, R.id.media_route_menu_item);
        return true;
    }

    public void onSelectButtonClick(final View v) {
        stopCastServer();
        Intent intent = new Intent(Intent.ACTION_PICK);
        if (v == selectButtonVideo) {
            intent.setDataAndType(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.CONTENT_TYPE);
            photoMode = false;
        } else {
            intent.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Images.Media.CONTENT_TYPE);
            photoMode = true;
        }
        startActivityForResult(intent, 0);
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != 0) {
            @SuppressWarnings("deprecation")
            Cursor c = managedQuery(data.getData(), null, null, null, null);
            if (c.moveToFirst()) {
                try {
                    mediaName = c.getString(1);
                    loadMedia();
                } catch (Exception e) {
                    Log.d(TAG, "Unexpected exception " + e.getMessage());
                }
            }
        }
    }

    private void loadMedia() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        @SuppressWarnings("deprecation")
        String ip = Formatter.formatIpAddress(wm.getConnectionInfo().getIpAddress());
        ((TextView) findViewById(R.id.tv_ip)).setText(ip);

        String filename = "";
        int slash = mediaName.lastIndexOf('/');
        if (slash > 1) {
            filename = mediaName.substring(slash + 1);
        }
        String rootDir = ".";
        if (slash > 1) {
            rootDir = mediaName.substring(0, slash);
        }
        startCastServer(ip, rootDir);

        String url = "http://" + ip + ":" + CastServerService.SERVER_PORT + "/" + filename;

        MediaMetadata mediaMetadata;
        if (photoMode) {
            mediaMetadata = new MediaMetadata(MediaMetadata.MEDIA_TYPE_PHOTO);
        } else {
            mediaMetadata = new MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE);
        }

        mediaMetadata.putString(MediaMetadata.KEY_TITLE, filename);
        MediaInfo mediaInfo;
        if (photoMode) {
            mediaInfo = new MediaInfo.Builder(url)
                    .setContentType("image/jpeg")
                    .setStreamType(MediaInfo.STREAM_TYPE_NONE)
                    .setMetadata(mediaMetadata)
                    .build();
        } else {
            mediaInfo = new MediaInfo.Builder(url)
                    .setContentType("video/mp4")
                    .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                    .setMetadata(mediaMetadata)
//                    .setStreamDuration(selectedMedia.getDuration() * 1000)
                    .build();
        }

        remoteMediaClient = castSession.getRemoteMediaClient();
        remoteMediaClient.addListener(clientListener);
        remoteMediaClient.load(mediaInfo, true, 0);
    }

    // WEB SERVER
    private void startCastServer(final String ip, final String rootDir) {
        Intent castServerService = new Intent(this, CastServerService.class);
        castServerService.putExtra(CastServerService.IP_ADDRESS, ip);
        castServerService.putExtra(CastServerService.ROOT_DIR, rootDir);
        startService(castServerService);
        serverButtonLight.setBackgroundColor(Color.GREEN);
    }

    private void stopCastServer() {
        Intent castServerService = new Intent(this, CastServerService.class);
        stopService(castServerService);
        serverButtonLight.setBackgroundColor(Color.RED);
    }
}
